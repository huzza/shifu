/*
 * Copyright [2012-2014] PayPal Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ml.shifu.shifu;

import ml.shifu.guagua.util.NumberFormatUtils;
import ml.shifu.shifu.container.obj.RawSourceData.SourceType;
import ml.shifu.shifu.fs.ShifuFileUtils;
import ml.shifu.shifu.util.CommonUtils;
import ml.shifu.shifu.util.Constants;
import ml.shifu.shifu.util.Environment;
import org.apache.commons.io.FileUtils;
import org.apache.hadoop.fs.Path;
import org.easymock.EasyMock;
import org.powermock.api.easymock.PowerMock;
import org.powermock.modules.testng.PowerMockObjectFactory;
import org.testng.Assert;
import org.testng.IObjectFactory;
import org.testng.annotations.AfterTest;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.ObjectFactory;
import org.testng.annotations.Test;

import java.io.*;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;

/**
 * ManagerTest class
 */
public class ShifuCLITest {

    @ObjectFactory
    public IObjectFactory setObjectFactory() {
        return new PowerMockObjectFactory();
    }

    @BeforeClass
    public void setUp() {
        Environment.setProperty(Environment.SHIFU_HOME, ".");
    }

    // @Test
    public void testInitializeModelOld() throws Exception {
        File modelConfigFile = new File("src/test/resources/data/ModelStore/ModelSet1/ModelConfig.json");
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(modelConfigFile), Constants.DEFAULT_CHARSET));

        File variableStoreFile = new File("src/test/resources/common/VariableStore.json");
        BufferedReader reader2 = new BufferedReader(
                new InputStreamReader(new FileInputStream(variableStoreFile), Constants.DEFAULT_CHARSET));

        String[] headers = "id|diagnosis|column_3|column_4|column_5|column_6|column_7|column_8|column_9|column_10|column_11|column_12|column_13|column_14|column_15|column_16|column_17|column_18|column_19|column_20|column_21|column_22|column_23|column_24|column_25|column_26|column_27|column_28|column_29|column_30|column_31|column_32|result"
                .split("\\|");

        PowerMock.mockStaticPartial(CommonUtils.class, "getReader", "getHeaders");

        EasyMock.expect(ShifuFileUtils.getReader("./ModelConfig.json", SourceType.LOCAL)).andReturn(reader).anyTimes();
        EasyMock.expect(ShifuFileUtils.getReader("common/VariableStore.json", SourceType.LOCAL)).andReturn(reader2)
                .anyTimes();
        EasyMock.expect(CommonUtils.getHeaders("./src/test/resources/data/DataStore/DataSet1/.pig_header", "|",
                SourceType.LOCAL)).andReturn(headers).anyTimes();

        PowerMock.replayAll(CommonUtils.class);

        ShifuCLI.initializeModel();

        File columnConfig = new File("./ColumnConfig.json");
        File tmp = new File("tmp");

        Assert.assertTrue(columnConfig.exists());
        Assert.assertTrue(tmp.exists());

        columnConfig.deleteOnExit();
        FileUtils.deleteDirectory(tmp);

        reader.close();
        reader2.close();
    }

    @Test
    public void testCreateModel() throws Exception {
        Environment.setProperty(Environment.SHIFU_HOME, "src/test/resources");
        ShifuCLI.createNewModel("TestModel", null, "It's a model for Unittest");

        File file = new File("TestModel");
        Assert.assertTrue(file.exists());
        FileUtils.deleteDirectory(file);
    }

    @Test
    public void testInitializeModel() throws Exception {
        File originModel = new File(
                "src/test/resources/example/cancer-judgement/ModelStore/ModelSet1/ModelConfig.json");
        File tmpModel = new File("ModelConfig.json");

        FileUtils.copyFile(originModel, tmpModel);

        ShifuCLI.initializeModel();

        File file = new File("ColumnConfig.json");
        Assert.assertTrue(file.exists());
        FileUtils.deleteQuietly(file);
        FileUtils.deleteQuietly(tmpModel);
    }

    @Test
    public void testCalculateModelStats() throws Exception {
        File originModel = new File(
                "src/test/resources/example/cancer-judgement/ModelStore/ModelSet1/ModelConfig.json");
        File tmpModel = new File("ModelConfig.json");

        File originColumn = new File(
                "src/test/resources/example/cancer-judgement/ModelStore/ModelSet1/ColumnConfig.json");
        File tmpColumn = new File("ColumnConfig.json");

        FileUtils.copyFile(originModel, tmpModel);
        FileUtils.copyFile(originColumn, tmpColumn);
        long timestamp = tmpColumn.lastModified();

        ShifuCLI.initializeModel();
        ShifuCLI.calModelStats(null);

        Assert.assertTrue(tmpColumn.lastModified() > timestamp);
        FileUtils.deleteQuietly(tmpModel);
        FileUtils.deleteQuietly(tmpColumn);
    }

    // @Test
    // comment out because of no normalized data
    public void testSelectModelVar() throws Exception {
        File originModel = new File(
                "src/test/resources/example/cancer-judgement/ModelStore/ModelSet1/ModelConfig.json");
        File tmpModel = new File("ModelConfig.json");

        File originColumn = new File(
                "src/test/resources/example/cancer-judgement/ModelStore/ModelSet1/ColumnConfig.json");
        File tmpColumn = new File("ColumnConfig.json");

        FileUtils.copyFile(originModel, tmpModel);
        FileUtils.copyFile(originColumn, tmpColumn);

        long timestamp = tmpColumn.lastModified();
        ShifuCLI.selectModelVar(null);
        Assert.assertTrue(tmpColumn.lastModified() > timestamp);

        FileUtils.deleteQuietly(tmpModel);
        FileUtils.deleteQuietly(tmpColumn);
    }

    @Test
    public void testNormalizeData() throws Exception {
        File originModel = new File(
                "src/test/resources/example/cancer-judgement/ModelStore/ModelSet1/ModelConfig.json");
        File tmpModel = new File("ModelConfig.json");

        File originColumn = new File(
                "src/test/resources/example/cancer-judgement/ModelStore/ModelSet1/ColumnConfig.json");
        File tmpColumn = new File("ColumnConfig.json");

        FileUtils.copyFile(originModel, tmpModel);
        FileUtils.copyFile(originColumn, tmpColumn);

        ShifuCLI.initializeModel();
        ShifuCLI.calModelStats(null);
        ShifuCLI.normalizeTrainData();

        File normalizedData = new File("tmp/NormalizedData");
        File selectedData = new File("tmp/SelectedRawData");
        Assert.assertTrue(normalizedData.exists());
        Assert.assertTrue(selectedData.exists());

        FileUtils.deleteQuietly(tmpModel);
        FileUtils.deleteQuietly(tmpColumn);
        FileUtils.deleteDirectory(new File("tmp"));
    }

    @Test
    public void testTrainModel() throws Exception {
        File originModel = new File(
                "src/test/resources/example/cancer-judgement/ModelStore/ModelSet1/ModelConfig.json");
        File tmpModel = new File("ModelConfig.json");

        File originColumn = new File(
                "src/test/resources/example/cancer-judgement/ModelStore/ModelSet1/ColumnConfig.json");
        File tmpColumn = new File("ColumnConfig.json");

        FileUtils.copyFile(originModel, tmpModel);
        FileUtils.copyFile(originColumn, tmpColumn);
        // run normalization
        ShifuCLI.normalizeTrainData();

        // run train
        ShifuCLI.trainModel(false);

        File modelFile = new File("models/model0.nn");
        Assert.assertTrue(modelFile.exists());

        try {
            FileUtils.deleteQuietly(tmpModel);
            FileUtils.deleteQuietly(tmpColumn);
            FileUtils.deleteDirectory(new File("tmp"));
            FileUtils.deleteDirectory(new File("models"));
        } catch (Exception ignore) {
        }
    }

    // @Test
    public void testPostTrainModel() throws Exception {
        File originModel = new File(
                "src/test/resources/example/cancer-judgement/ModelStore/ModelSet1/ModelConfig.json");
        File tmpModel = new File("ModelConfig.json");

        File originColumn = new File(
                "src/test/resources/example/cancer-judgement/ModelStore/ModelSet1/ColumnConfig.json");
        File tmpColumn = new File("ColumnConfig.json");

        File modelsDir = new File("src/test/resources/example/cancer-judgement/ModelStore/ModelSet1/models");
        File tmpModelsDir = new File("models");

        FileUtils.copyFile(originModel, tmpModel);
        FileUtils.copyFile(originColumn, tmpColumn);
        FileUtils.copyDirectory(modelsDir, tmpModelsDir);

        long timestamp = tmpColumn.lastModified();
        // run post-train
        ShifuCLI.initializeModel();
        ShifuCLI.calModelStats(null);
        ShifuCLI.normalizeTrainData();
        ShifuCLI.selectModelVar(null);
        ShifuCLI.postTrainModel();
        Assert.assertTrue(tmpColumn.lastModified() > timestamp);

        FileUtils.deleteQuietly(tmpModel);
        FileUtils.deleteQuietly(tmpColumn);
        FileUtils.deleteDirectory(new File("tmp"));
        FileUtils.deleteDirectory(new File("models"));
    }

    @Test
    public void testRunEvalAll() throws Exception {
        File originModel = new File(
                "src/test/resources/example/cancer-judgement/ModelStore/ModelSet1/ModelConfig.json");
        File tmpModel = new File("ModelConfig.json");

        File originColumn = new File(
                "src/test/resources/example/cancer-judgement/ModelStore/ModelSet1/ColumnConfig.json");
        File tmpColumn = new File("ColumnConfig.json");

        File modelsDir = new File("src/test/resources/example/cancer-judgement/ModelStore/ModelSet1/models");
        File tmpModelsDir = new File("models");

        FileUtils.copyFile(originModel, tmpModel);
        FileUtils.copyFile(originColumn, tmpColumn);
        FileUtils.copyDirectory(modelsDir, tmpModelsDir);

        // run evaluation set
        ShifuCLI.runEvalSet(new HashMap<String, Object>());
        File evalScore = new File("evals/EvalA/EvalScore");
        Assert.assertTrue(evalScore.exists());

        FileUtils.deleteQuietly(tmpModel);
        FileUtils.deleteQuietly(tmpColumn);
        FileUtils.deleteQuietly(new File("evals/EvalA/EvalConfusionMatrix"));
        FileUtils.deleteQuietly(new File("evals/EvalB/EvalConfusionMatrix"));
    }

    @Test
    public void testCreateEvalSet() throws Exception {
        File originModel = new File(
                "src/test/resources/example/cancer-judgement/ModelStore/ModelSet1/ModelConfig.json");
        File tmpModel = new File("ModelConfig.json");
        File originColumn = new File(
                "src/test/resources/example/cancer-judgement/ModelStore/ModelSet1/ColumnConfig.json");
        File tmpColumn = new File("ColumnConfig.json");

        FileUtils.copyFile(originModel, tmpModel);
        FileUtils.copyFile(originColumn, tmpColumn);

        long timestamp = tmpModel.lastModified();
        // run create evaluation set
        ShifuCLI.createNewEvalSet("EvalC");
        Assert.assertTrue(tmpModel.lastModified() > timestamp);

        FileUtils.deleteQuietly(tmpModel);
        FileUtils.deleteQuietly(new File("EvalC" + Constants.DEFAULT_CHAMPIONSCORE_META_COLUMN_FILE));
        FileUtils.deleteDirectory(new File("columns"));
    }

    @Test
    public void testRunExport() throws Exception {
        File originModel = new File(
                "src/test/resources/example/cancer-judgement/ModelStore/ModelSet1/ModelConfig.json");
        File tmpModel = new File("ModelConfig.json");

        File originColumn = new File(
                "src/test/resources/example/cancer-judgement/ModelStore/ModelSet1/ColumnConfig.json");

        File tmpColumn = new File("ColumnConfig.json");

        File modelsDir = new File("src/test/resources/example/cancer-judgement/ModelStore/ModelSet1/models");
        File tmpModelsDir = new File("models");

        FileUtils.copyFile(originModel, tmpModel);
        FileUtils.copyFile(originColumn, tmpColumn);
        FileUtils.copyDirectory(modelsDir, tmpModelsDir);

        // run evaluation set
        ShifuCLI.exportModel(null, null);

        File pmml = new File("./pmmls/cancer-judgement0.pmml");
        Assert.assertTrue(pmml.exists());

        FileUtils.deleteQuietly(tmpModel);
        FileUtils.deleteQuietly(tmpColumn);
        FileUtils.deleteQuietly(new File("./pmmls"));
        FileUtils.deleteDirectory(tmpModelsDir);
    }

    @AfterTest
    public void delete() throws IOException {
        FileUtils.deleteDirectory(new File("evals"));
    }

    @Test
    public void testSplit() {
        System.out.println(Arrays.asList("01Jun2015^0.3456".split("\\^")));
    }

    @Test
    public void testLastIndexOf() {
        String text = "aa^bb^cc";
        Assert.assertEquals(2, text.lastIndexOf("^", 4));
        Assert.assertEquals(-1, text.lastIndexOf("~", 4));

        double a = Double.parseDouble("NaN");
        System.out.println(a);

        float t = NumberFormatUtils.getFloat("1", 0f);
        Assert.assertTrue(isPositive(t));
    }

    protected boolean isPositive(float value) {
        return Float.compare(1f, value) == 0 ? true : false;
    }

    @Test
    public void testConvert() {
        ShifuCLI.runShifuConvert(1, "src/test/resources/example/readablespec/model0.gbt", "/tmp/model0_1.zip");
    }

    @Test
    public void testAnalysis() {
        int ret = ShifuCLI.analysisModelFi("src/test/resources/example/readablespec/model0.gbt");
        Assert.assertEquals(ret, 0);

        File file = new File("model0.gbt.fi");
        Assert.assertTrue(file.exists());
        FileUtils.deleteQuietly(file);
    }

    @Test
    public void testConvertDate() {
        long ts = 1601103600l * 1000l;
        Date date = new Date(ts);
        System.out.println(date);

        Assert.assertEquals("model0".replaceAll("\\..*$", ""), "model0");
        Assert.assertEquals("model0.".replaceAll("\\..*$", ""), "model0");
        Assert.assertEquals("model0.gbt".replaceAll("\\..*$", ""), "model0");
        Assert.assertEquals("model0.nn.zip".replaceAll("\\..*$", ""), "model0");
    }

    @Test
    public void testPath() {
        Path filePath = new Path("tmp/354240.log");
        String schema = filePath.toUri().getScheme();
        String host = filePath.toUri().getHost();
        System.out.println("Schema is " + schema);
        System.out.println("Host is " + host);
    }
}
