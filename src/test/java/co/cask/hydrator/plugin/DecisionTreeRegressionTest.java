/*
 * Copyright © 2016 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.hydrator.plugin;

import co.cask.cdap.api.data.format.StructuredRecord;
import co.cask.cdap.api.data.schema.Schema;
import co.cask.cdap.api.dataset.table.Table;
import co.cask.cdap.datapipeline.DataPipelineApp;
import co.cask.cdap.datapipeline.SmartWorkflow;
import co.cask.cdap.etl.api.batch.SparkCompute;
import co.cask.cdap.etl.api.batch.SparkSink;
import co.cask.cdap.etl.mock.batch.MockSink;
import co.cask.cdap.etl.mock.batch.MockSource;
import co.cask.cdap.etl.mock.test.HydratorTestBase;
import co.cask.cdap.etl.proto.v2.ETLBatchConfig;
import co.cask.cdap.etl.proto.v2.ETLPlugin;
import co.cask.cdap.etl.proto.v2.ETLStage;
import co.cask.cdap.proto.artifact.AppRequest;
import co.cask.cdap.proto.artifact.ArtifactSummary;
import co.cask.cdap.proto.id.ApplicationId;
import co.cask.cdap.proto.id.ArtifactId;
import co.cask.cdap.proto.id.NamespaceId;
import co.cask.cdap.test.ApplicationManager;
import co.cask.cdap.test.DataSetManager;
import co.cask.cdap.test.TestConfiguration;
import co.cask.cdap.test.WorkflowManager;
import com.google.common.collect.ImmutableMap;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Unit tests for {@link DecisionTreeTrainer} and {@link DecisionTreePredictor} classes.
 */
public class DecisionTreeRegressionTest extends HydratorTestBase {

  @ClassRule
  public static final TestConfiguration CONFIG = new TestConfiguration("explore.enabled", false);

  protected static final ArtifactId DATAPIPELINE_ARTIFACT_ID = NamespaceId.DEFAULT.artifact("data-pipeline", "4.0.0");
  protected static final ArtifactSummary DATAPIPELINE_ARTIFACT = new ArtifactSummary("data-pipeline", "4.0.0");
  private static final String LABELED_RECORDS = "labeledRecords";
  private final Schema schema =
    Schema.recordOf("flightData", Schema.Field.of("dofM", Schema.nullableOf(Schema.of(Schema.Type.INT))),
                    Schema.Field.of("dofW", Schema.nullableOf(Schema.of(Schema.Type.INT))),
                    Schema.Field.of("carrier", Schema.nullableOf(Schema.of(Schema.Type.DOUBLE))),
                    Schema.Field.of("tailNum", Schema.nullableOf(Schema.of(Schema.Type.STRING))),
                    Schema.Field.of("flightNum", Schema.nullableOf(Schema.of(Schema.Type.INT))),
                    Schema.Field.of("originId", Schema.nullableOf(Schema.of(Schema.Type.INT))),
                    Schema.Field.of("origin", Schema.nullableOf(Schema.of(Schema.Type.STRING))),
                    Schema.Field.of("destId", Schema.nullableOf(Schema.of(Schema.Type.INT))),
                    Schema.Field.of("dest", Schema.nullableOf(Schema.of(Schema.Type.STRING))),
                    Schema.Field.of("scheduleDepTime", Schema.nullableOf(Schema.of(Schema.Type.DOUBLE))),
                    Schema.Field.of("deptime", Schema.nullableOf(Schema.of(Schema.Type.DOUBLE))),
                    Schema.Field.of("depDelayMins", Schema.nullableOf(Schema.of(Schema.Type.DOUBLE))),
                    Schema.Field.of("scheduledArrTime", Schema.nullableOf(Schema.of(Schema.Type.DOUBLE))),
                    Schema.Field.of("arrTime", Schema.nullableOf(Schema.of(Schema.Type.DOUBLE))),
                    Schema.Field.of("arrDelay", Schema.nullableOf(Schema.of(Schema.Type.DOUBLE))),
                    Schema.Field.of("elapsedTime", Schema.nullableOf(Schema.of(Schema.Type.DOUBLE))),
                    Schema.Field.of("distance", Schema.nullableOf(Schema.of(Schema.Type.INT))));

  @BeforeClass
  public static void setupTest() throws Exception {
    // add the artifact for etl batch app
    setupBatchArtifacts(DATAPIPELINE_ARTIFACT_ID, DataPipelineApp.class);
    // add artifact for spark plugins
    addPluginArtifact(NamespaceId.DEFAULT.artifact("decision-tree-analytics-plugin", "1.0.0"), DATAPIPELINE_ARTIFACT_ID,
                      DecisionTreeTrainer.class, DecisionTreePredictor.class);
  }

  @Test
  public void testSparkSinkAndCompute() throws Exception {
    // use the SparkSink(DecisionTreeTrainer) to train a model
    testSinglePhaseWithSparkSink();
    // use a SparkCompute(DecisionTreePredictor) to label all records going through the pipeline, using the model
    // build with the SparkSink
    testSinglePhaseWithSparkCompute();
  }

  private void testSinglePhaseWithSparkSink() throws Exception {
    /*
     * source --> sparksink
     */
    Map<String, String> properties = new ImmutableMap.Builder<String, String>()
      .put("fileSetName", "decision-tree-regression-model")
      .put("featureFieldsToInclude", "dofM,dofW,carrier,originId,destId,scheduleDepTime,scheduledArrTime,elapsedTime")
      .put("cardinalityMapping", "dofW:7")
      .put("labelField", "delayed")
      .put("maxBins", "100")
      .put("maxDepth", "9")
      .build();

    ETLBatchConfig etlConfig = ETLBatchConfig.builder("* * * * *")
      .addStage(new ETLStage("source", MockSource.getPlugin("flightRecords", getTrainerSchema(schema))))
      .addStage(new ETLStage("customsink", new ETLPlugin(DecisionTreeTrainer.PLUGIN_NAME, SparkSink.PLUGIN_TYPE,
                                                         properties, null)))
      .addConnection("source", "customsink")
      .build();

    AppRequest<ETLBatchConfig> appRequest = new AppRequest<>(DATAPIPELINE_ARTIFACT, etlConfig);
    ApplicationId appId = NamespaceId.DEFAULT.app("SinglePhaseApp");
    ApplicationManager appManager = deployApplication(appId.toId(), appRequest);

    // send records from sample data to train the model
    List<StructuredRecord> messagesToWrite = new ArrayList<>();
    messagesToWrite.addAll(getInputData());

    // write records to source
    DataSetManager<Table> inputManager = getDataset(NamespaceId.DEFAULT.dataset("flightRecords"));
    MockSource.writeInput(inputManager, messagesToWrite);

    // manually trigger the pipeline
    WorkflowManager workflowManager = appManager.getWorkflowManager(SmartWorkflow.NAME);
    workflowManager.start();
    workflowManager.waitForFinish(5, TimeUnit.MINUTES);
  }

  //Get data from file to be used for training the model.
  private List<StructuredRecord> getInputData() throws IOException {
    List<StructuredRecord> messagesToWrite = new ArrayList<>();
    File file = new File(this.getClass().getResource("/trainData.csv").getFile());
    BufferedReader bufferedInputStream = new BufferedReader(new FileReader(file));
    String line;
    while ((line = bufferedInputStream.readLine()) != null) {
      String[] flightData = line.split(",");
      Double depDelayMins = Double.parseDouble(flightData[11]);
      //For binary classification create delayed field containing values 1.0 and 0.0 depending on the delay time.
      double delayed = depDelayMins > 40 ? 1.0 : 0.0;
      messagesToWrite.add(new Flight(Integer.parseInt(flightData[0]) - 1, Integer.parseInt(flightData[1]) - 1,
                                     Double.parseDouble(flightData[2]), flightData[3], Integer.parseInt(flightData[4]),
                                     Integer.parseInt(flightData[5]), flightData[6], Integer.parseInt(flightData[7]),
                                     flightData[8], Integer.parseInt(flightData[9]), Double.parseDouble(flightData[10]),
                                     depDelayMins, Double.parseDouble(flightData[12]),
                                     Double.parseDouble(flightData[13]), Double.parseDouble(flightData[14]),
                                     Double.parseDouble(flightData[15]), Integer.parseInt(flightData[16]), delayed)
                            .toStructuredRecord());
    }
    return messagesToWrite;
  }

  private void testSinglePhaseWithSparkCompute() throws Exception {
    String features = "features";
    /*
     * source --> sparkcompute --> sink
     */
    ETLBatchConfig etlConfig = ETLBatchConfig.builder("* * * * *")
      .addStage(new ETLStage("source", MockSource.getPlugin(features, schema)))
      .addStage(new ETLStage("sparkcompute",
                             new ETLPlugin(DecisionTreePredictor.PLUGIN_NAME, SparkCompute.PLUGIN_TYPE,
                                           ImmutableMap.of("fileSetName", "decision-tree-regression-model",
                                                           "featureFieldsToExclude", "tailNum,flightNum,origin,dest," +
                                                             "deptime,depDelayMins,arrTime,arrDelay,distance",
                                                           "predictionField", "delayed"), null)))
      .addStage(new ETLStage("sink", MockSink.getPlugin(LABELED_RECORDS)))
      .addConnection("source", "sparkcompute")
      .addConnection("sparkcompute", "sink")
      .build();

    AppRequest<ETLBatchConfig> appRequest = new AppRequest<>(DATAPIPELINE_ARTIFACT, etlConfig);
    ApplicationId appId = NamespaceId.DEFAULT.app("SinglePhaseApp");
    ApplicationManager appManager = deployApplication(appId.toId(), appRequest);

    // Flight records to be labeled.
    Set<StructuredRecord> messagesToWrite = new HashSet<>();
    messagesToWrite.add(new Flight(3, 5, 1.0, "N327AA", 1, 12478, "JFK", 12892, "LAX", 900, 1005.0, 65.0,
                                   1225.0, 1324.0, 59.0, 385.0, 2475).toStructuredRecord());
    messagesToWrite.add(new Flight(24, 5, 2.0, "N0EGMQ", 3419, 10397, "ATL", 12953, "LGA", 1150, 1229.0, 39.0,
                                   1359.0, 1448.0, 49.0, 129.0, 762).toStructuredRecord());
    messagesToWrite.add(new Flight(3, 5, 3.0, "N14991", 6159, 13930, "ORD", 13198, "MCI", 2030, 2118.0, 48.0,
                                   2205.0, 2321.0, 76.0, 95.0, 403).toStructuredRecord());
    messagesToWrite.add(new Flight(28, 2, 1.0, "N355AA", 2407, 12892, "LAX", 11298, "DFW", 1025, 1023.0, 0.0,
                                   1530.0, 1523.0, 0.0, 185.0, 1235).toStructuredRecord());
    messagesToWrite.add(new Flight(1, 3, 4.0, "N919DE", 1908, 13930, "ORD", 11433, "DTW", 1641, 1902.0, 141.0,
                                   1905.0, 2117.0, 132.0, 84.0, 235).toStructuredRecord());
    messagesToWrite.add(new Flight(1, 3, 4.0, "N933DN", 1791, 10397, "ATL", 15376, "TUS", 1855, 2014.0, 79.0,
                                   2108.0, 2159.0, 51.0, 253.0, 1541).toStructuredRecord());

    DataSetManager<Table> inputManager = getDataset(NamespaceId.DEFAULT.dataset(features));
    MockSource.writeInput(inputManager, messagesToWrite);

    // manually trigger the pipeline
    WorkflowManager workflowManager = appManager.getWorkflowManager(SmartWorkflow.NAME);
    workflowManager.start();
    workflowManager.waitForFinish(5, TimeUnit.MINUTES);

    DataSetManager<Table> labeledTexts = getDataset(LABELED_RECORDS);
    List<StructuredRecord> structuredRecords = MockSink.readOutput(labeledTexts);

    Set<Flight> results = new HashSet<>();
    for (StructuredRecord structuredRecord : structuredRecords) {
      results.add(Flight.fromStructuredRecord(structuredRecord));
    }

    Set<Flight> expected = new HashSet<>();
    expected.add(new Flight(3, 5, 1.0, "N327AA", 1, 12478, "JFK", 12892, "LAX", 900, 1005.0, 65.0, 1225.0, 1324.0, 59.0,
                            385.0, 2475, 1.0));
    expected.add(new Flight(28, 2, 1.0, "N355AA", 2407, 12892, "LAX", 11298, "DFW", 1025, 1023.0, 0.0, 1530.0, 1523.0,
                            0.0, 185.0, 1235, 0.0));
    expected.add(new Flight(3, 5, 3.0, "N14991", 6159, 13930, "ORD", 13198, "MCI", 2030, 2118.0, 48.0, 2205.0, 2321.0,
                            76.0, 95.0, 403, 1.0));
    expected.add(new Flight(24, 5, 2.0, "N0EGMQ", 3419, 10397, "ATL", 12953, "LGA", 1150, 1229.0, 39.0, 1359.0, 1448.0,
                            49.0, 129.0, 762, 0.0));
    expected.add(new Flight(1, 3, 4.0, "N919DE", 1908, 13930, "ORD", 11433, "DTW", 1641, 1902.0, 141.0, 1905.0, 2117.0,
                            132.0, 84.0, 235, 1.0));
    expected.add(new Flight(1, 3, 4.0, "N933DN", 1791, 10397, "ATL", 15376, "TUS", 1855, 2014.0, 79.0, 2108.0, 2159.0,
                            51.0, 253.0, 1541, 1.0));
    Assert.assertEquals(expected, results);
  }

  @Test
  public void testInvalidCardinalityMapping() throws Exception {
    /*
     * source --> sparksink
     */
    Map<String, String> properties = new ImmutableMap.Builder<String, String>()
      .put("fileSetName", "decision-tree-regression-model")
      .put("path", "decisionTreeRegression")
      .put("featureFieldsToInclude", "dofM,dofW,carrier,originId,destId,scheduleDepTime,scheduledArrTime,elapsedTime")
      .put("cardinalityMapping", "dofW:2")
      .put("labelField", "delayed")
      .put("maxBins", "100")
      .put("maxDepth", "9")
      .build();

    ETLBatchConfig etlConfig = ETLBatchConfig.builder("* * * * *")
      .addStage(new ETLStage("source", MockSource.getPlugin("flightRecords", getTrainerSchema(schema))))
      .addStage(new ETLStage("customsink", new ETLPlugin(DecisionTreeTrainer.PLUGIN_NAME, SparkSink.PLUGIN_TYPE,
                                                         properties, null)))
      .addConnection("source", "customsink")
      .build();

    AppRequest<ETLBatchConfig> appRequest = new AppRequest<>(DATAPIPELINE_ARTIFACT, etlConfig);
    ApplicationId appId = NamespaceId.DEFAULT.app("SinglePhaseApp");
    ApplicationManager appManager = deployApplication(appId.toId(), appRequest);

    // send records from sample data to train the model
    List<StructuredRecord> messagesToWrite = new ArrayList<>();
    messagesToWrite.addAll(getInputData());

    // write records to source
    DataSetManager<Table> inputManager = getDataset(NamespaceId.DEFAULT.dataset("flightRecords"));
    MockSource.writeInput(inputManager, messagesToWrite);

    // manually trigger the pipeline
    WorkflowManager workflowManager = appManager.getWorkflowManager(SmartWorkflow.NAME);
    workflowManager.start();
    workflowManager.waitForFinish(5, TimeUnit.MINUTES);

    Assert.assertEquals("FAILED", workflowManager.getHistory().get(0).getStatus().name());
  }

  private Schema getTrainerSchema(Schema schema) {
    List<Schema.Field> fields = new ArrayList<>(schema.getFields());
    fields.add(Schema.Field.of("delayed", Schema.nullableOf(Schema.of(Schema.Type.DOUBLE))));
    return Schema.recordOf(schema.getRecordName() + ".predicted", fields);
  }
}
