/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.beam.sdk.io.gcp.bigquery;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.api.client.util.BackOff;
import com.google.api.client.util.BackOffUtils;
import com.google.api.client.util.Sleeper;
import com.google.api.services.bigquery.model.Job;
import com.google.api.services.bigquery.model.JobConfigurationLoad;
import com.google.api.services.bigquery.model.JobReference;
import com.google.api.services.bigquery.model.TableReference;
import com.google.api.services.bigquery.model.TableSchema;
import com.google.api.services.bigquery.model.TimePartitioning;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.coders.VoidCoder;
import org.apache.beam.sdk.io.FileSystems;
import org.apache.beam.sdk.io.fs.ResourceId;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryHelpers.RetryJobId;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryHelpers.RetryJobIdResult;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryHelpers.Status;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO.Write.CreateDisposition;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO.Write.WriteDisposition;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryServices.DatasetService;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryServices.JobService;
import org.apache.beam.sdk.options.ValueProvider;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.GroupByKey;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.Values;
import org.apache.beam.sdk.transforms.WithKeys;
import org.apache.beam.sdk.transforms.windowing.AfterPane;
import org.apache.beam.sdk.transforms.windowing.GlobalWindows;
import org.apache.beam.sdk.transforms.windowing.Repeatedly;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.util.BackOffAdapter;
import org.apache.beam.sdk.util.FluentBackoff;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.beam.sdk.values.ShardedKey;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.TupleTagList;
import org.joda.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes partitions to BigQuery tables.
 *
 * <p>The input is a list of files corresponding to each partition of a table. loadThese files are
 * loaded into a temporary table (or into the final table if there is only one partition). The
 * output is a {@link KV} mapping each final table to a list of the temporary tables containing its
 * data.
 *
 * <p>In the case where all the data in the files fit into a single load job, this transform loads
 * the data directly into the final table, skipping temporary tables. In this case, the output
 * {@link KV} maps the final table to itself.
 */
class WriteTables<DestinationT>
    extends PTransform<
        PCollection<KV<ShardedKey<DestinationT>, List<String>>>,
        PCollection<KV<TableDestination, String>>> {
  private static final Logger LOG = LoggerFactory.getLogger(WriteTables.class);

  private final boolean singlePartition;
  private final BigQueryServices bqServices;
  private final PCollectionView<String> loadJobIdPrefixView;
  private final WriteDisposition firstPaneWriteDisposition;
  private final CreateDisposition firstPaneCreateDisposition;
  private final DynamicDestinations<?, DestinationT> dynamicDestinations;
  private final List<PCollectionView<?>> sideInputs;
  private final TupleTag<KV<TableDestination, String>> mainOutputTag;
  private final TupleTag<String> temporaryFilesTag;
  private final ValueProvider<String> loadJobProjectId;
  private final int maxRetryJobs;

  private class WriteTablesDoFn
      extends DoFn<KV<ShardedKey<DestinationT>, List<String>>, KV<TableDestination, String>> {
    private Map<DestinationT, String> jsonSchemas = Maps.newHashMap();

    @StartBundle
    public void startBundle(StartBundleContext c) {
      // Clear the map on each bundle so we can notice side-input updates.
      // (alternative is to use a cache with a TTL).
      jsonSchemas.clear();
    }

    @ProcessElement
    public void processElement(ProcessContext c) throws Exception {
      dynamicDestinations.setSideInputAccessorFromProcessContext(c);
      DestinationT destination = c.element().getKey().getKey();
      TableSchema tableSchema;
      if (firstPaneCreateDisposition == CreateDisposition.CREATE_NEVER) {
        tableSchema = null;
      } else if (jsonSchemas.containsKey(destination)) {
        tableSchema =
            BigQueryHelpers.fromJsonString(jsonSchemas.get(destination), TableSchema.class);
      } else {
        tableSchema = dynamicDestinations.getSchema(destination);
        checkArgument(
            tableSchema != null,
            "Unless create disposition is %s, a schema must be specified, i.e. "
                + "DynamicDestinations.getSchema() may not return null. "
                + "However, create disposition is %s, and %s returned null for destination %s",
            CreateDisposition.CREATE_NEVER,
            firstPaneCreateDisposition,
            dynamicDestinations,
            destination);
        jsonSchemas.put(destination, BigQueryHelpers.toJsonString(tableSchema));
      }

      TableDestination tableDestination = dynamicDestinations.getTable(destination);
      checkArgument(
          tableDestination != null,
          "DynamicDestinations.getTable() may not return null, "
              + "but %s returned null for destination %s",
          dynamicDestinations,
          destination);
      TableReference tableReference = tableDestination.getTableReference();
      if (Strings.isNullOrEmpty(tableReference.getProjectId())) {
        tableReference.setProjectId(c.getPipelineOptions().as(BigQueryOptions.class).getProject());
        tableDestination = tableDestination.withTableReference(tableReference);
      }

      Integer partition = c.element().getKey().getShardNumber();
      List<String> partitionFiles = Lists.newArrayList(c.element().getValue());
      String jobIdPrefix =
          BigQueryHelpers.createJobId(
              c.sideInput(loadJobIdPrefixView), tableDestination, partition, c.pane().getIndex());

      if (!singlePartition) {
        tableReference.setTableId(jobIdPrefix);
      }

      WriteDisposition writeDisposition =
          (c.pane().getIndex() == 0) ? firstPaneWriteDisposition : WriteDisposition.WRITE_APPEND;
      CreateDisposition createDisposition =
          (c.pane().getIndex() == 0) ? firstPaneCreateDisposition : CreateDisposition.CREATE_NEVER;
      load(
          bqServices.getJobService(c.getPipelineOptions().as(BigQueryOptions.class)),
          bqServices.getDatasetService(c.getPipelineOptions().as(BigQueryOptions.class)),
          jobIdPrefix,
          tableReference,
          tableDestination.getTimePartitioning(),
          tableSchema,
          partitionFiles,
          writeDisposition,
          createDisposition,
          tableDestination.getTableDescription());
      c.output(
          mainOutputTag, KV.of(tableDestination, BigQueryHelpers.toJsonString(tableReference)));
      for (String file : partitionFiles) {
        c.output(temporaryFilesTag, file);
      }
    }
  }

  private static class GarbageCollectTemporaryFiles extends DoFn<Iterable<String>, Void> {
    @ProcessElement
    public void processElement(ProcessContext c) throws Exception {
      removeTemporaryFiles(c.element());
    }
  }

  public WriteTables(
      boolean singlePartition,
      BigQueryServices bqServices,
      PCollectionView<String> loadJobIdPrefixView,
      WriteDisposition writeDisposition,
      CreateDisposition createDisposition,
      List<PCollectionView<?>> sideInputs,
      DynamicDestinations<?, DestinationT> dynamicDestinations,
      @Nullable ValueProvider<String> loadJobProjectId,
      int maxRetryJobs) {
    this.singlePartition = singlePartition;
    this.bqServices = bqServices;
    this.loadJobIdPrefixView = loadJobIdPrefixView;
    this.firstPaneWriteDisposition = writeDisposition;
    this.firstPaneCreateDisposition = createDisposition;
    this.sideInputs = sideInputs;
    this.dynamicDestinations = dynamicDestinations;
    this.mainOutputTag = new TupleTag<>("WriteTablesMainOutput");
    this.temporaryFilesTag = new TupleTag<>("TemporaryFiles");
    this.loadJobProjectId = loadJobProjectId;
    this.maxRetryJobs = maxRetryJobs;
  }

  @Override
  public PCollection<KV<TableDestination, String>> expand(
      PCollection<KV<ShardedKey<DestinationT>, List<String>>> input) {
    PCollectionTuple writeTablesOutputs =
        input.apply(
            ParDo.of(new WriteTablesDoFn())
                .withSideInputs(sideInputs)
                .withOutputTags(mainOutputTag, TupleTagList.of(temporaryFilesTag)));

    // Garbage collect temporary files.
    // We mustn't start garbage collecting files until we are assured that the WriteTablesDoFn has
    // succeeded in loading those files and won't be retried. Otherwise, we might fail part of the
    // way through deleting temporary files, and retry WriteTablesDoFn. This will then fail due
    // to missing files, causing either the entire workflow to fail or get stuck (depending on how
    // the runner handles persistent failures).
    writeTablesOutputs
        .get(temporaryFilesTag)
        .setCoder(StringUtf8Coder.of())
        .apply(WithKeys.of((Void) null))
        .setCoder(KvCoder.of(VoidCoder.of(), StringUtf8Coder.of()))
        .apply(
            Window.<KV<Void, String>>into(new GlobalWindows())
                .triggering(Repeatedly.forever(AfterPane.elementCountAtLeast(1)))
                .discardingFiredPanes())
        .apply(GroupByKey.create())
        .apply(Values.create())
        .apply(ParDo.of(new GarbageCollectTemporaryFiles()));

    return writeTablesOutputs.get(mainOutputTag);
  }

  private void load(
      JobService jobService,
      DatasetService datasetService,
      String jobIdPrefix,
      TableReference ref,
      TimePartitioning timePartitioning,
      @Nullable TableSchema schema,
      List<String> gcsUris,
      WriteDisposition writeDisposition,
      CreateDisposition createDisposition,
      @Nullable String tableDescription)
      throws InterruptedException, IOException {
    JobConfigurationLoad loadConfig =
        new JobConfigurationLoad()
            .setDestinationTable(ref)
            .setSchema(schema)
            .setSourceUris(gcsUris)
            .setWriteDisposition(writeDisposition.name())
            .setCreateDisposition(createDisposition.name())
            .setSourceFormat("NEWLINE_DELIMITED_JSON");
    if (timePartitioning != null) {
      loadConfig.setTimePartitioning(timePartitioning);
    }
    String projectId = loadJobProjectId == null ? ref.getProjectId() : loadJobProjectId.get();
    Job lastFailedLoadJob = null;
    String bqLocation =
        BigQueryHelpers.getDatasetLocation(datasetService, ref.getProjectId(), ref.getDatasetId());

    BackOff backoff =
        BackOffAdapter.toGcpBackOff(
            FluentBackoff.DEFAULT
                .withMaxRetries(maxRetryJobs)
                .withInitialBackoff(Duration.standardSeconds(1))
                .withMaxBackoff(Duration.standardMinutes(1))
                .backoff());
    Sleeper sleeper = Sleeper.DEFAULT;
    // First attempt is always jobIdPrefix-0.
    RetryJobId jobId = new RetryJobId(jobIdPrefix, 0);
    int i = 0;
    do {
      ++i;
      JobReference jobRef =
          new JobReference()
              .setProjectId(projectId)
              .setJobId(jobId.getJobId())
              .setLocation(bqLocation);

      LOG.info("Loading {} files into {} using job {}, attempt {}", gcsUris.size(), ref, jobRef, i);
      try {
        jobService.startLoadJob(jobRef, loadConfig);
      } catch (IOException e) {
        LOG.warn("Load job {} failed with {}", jobRef, e);
        // It's possible that the job actually made it to BQ even though we got a failure here.
        // For example, the response from BQ may have timed out returning. getRetryJobId will
        // return the correct job id to use on retry, or a job id to continue polling (if it turns
        // out the the job has not actually failed yet).
        RetryJobIdResult result =
            BigQueryHelpers.getRetryJobId(jobId, projectId, bqLocation, jobService);
        jobId = result.jobId;
        if (result.shouldRetry) {
          // Try the load again with the new job id.
          continue;
        }
        // Otherwise,the job has reached BigQuery and is in either the PENDING state or has
        // completed successfully.
      }
      LOG.info("Load job {} started", jobRef);
      // Try to wait until the job is done (succeeded or failed).
      Job loadJob = jobService.pollJob(jobRef, BatchLoads.LOAD_JOB_POLL_MAX_RETRIES);

      Status jobStatus = BigQueryHelpers.parseStatus(loadJob);
      switch (jobStatus) {
        case SUCCEEDED:
          LOG.info("Load job {} succeeded. Statistics: {}", jobRef, loadJob.getStatistics());
          if (tableDescription != null) {
            datasetService.patchTableDescription(
                ref.clone().setTableId(BigQueryHelpers.stripPartitionDecorator(ref.getTableId())),
                tableDescription);
          }
          return;
        case UNKNOWN:
          // This might happen if BigQuery's job listing is slow. Retry with the same
          // job id.
          LOG.info(
              "Load job {} finished in unknown state: {}: {}",
              jobRef,
              loadJob.getStatus(),
              (i < maxRetryJobs - 1) ? "will retry" : "will not retry");
          lastFailedLoadJob = loadJob;
          continue;
        case FAILED:
          lastFailedLoadJob = loadJob;
          jobId = BigQueryHelpers.getRetryJobId(jobId, projectId, bqLocation, jobService).jobId;
          LOG.info(
              "Load job {} failed, {}: {}. Next job id {}",
              jobRef,
              (i < maxRetryJobs - 1) ? "will retry" : "will not retry",
              loadJob.getStatus(),
              jobId);
          continue;
        default:
          throw new IllegalStateException(
              String.format(
                  "Unexpected status [%s] of load job: %s.",
                  loadJob.getStatus(), BigQueryHelpers.jobToPrettyString(loadJob)));
      }
    } while (nextBackOff(sleeper, backoff));
    throw new RuntimeException(
        String.format(
            "Failed to create load job with id prefix %s, "
                + "reached max retries: %d, last failed load job: %s.",
            jobIdPrefix, maxRetryJobs, BigQueryHelpers.jobToPrettyString(lastFailedLoadJob)));
  }

  /** Identical to {@link BackOffUtils#next} but without checked IOException. */
  private static boolean nextBackOff(Sleeper sleeper, BackOff backoff) throws InterruptedException {
    try {
      return BackOffUtils.next(sleeper, backoff);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  static void removeTemporaryFiles(Iterable<String> files) throws IOException {
    ImmutableList.Builder<ResourceId> fileResources = ImmutableList.builder();
    for (String file : files) {
      fileResources.add(FileSystems.matchNewResource(file, false /* isDirectory */));
    }
    FileSystems.delete(fileResources.build());
  }
}
