package gov.usgs.cida.gdp.wps.algorithm;

import gov.usgs.cida.gdp.constants.AppConstant;
import gov.usgs.cida.gdp.wps.binding.GMLStreamingFeatureCollectionBinding;
import gov.usgs.cida.gdp.wps.binding.GeoTIFFFileBinding;
import gov.usgs.cida.gdp.wps.util.WCSUtil;

import java.io.File;
import java.net.URI;

import org.geotools.feature.FeatureCollection;
import org.n52.wps.algorithm.annotation.Algorithm;
import org.n52.wps.algorithm.annotation.ComplexDataInput;
import org.n52.wps.algorithm.annotation.ComplexDataOutput;
import org.n52.wps.algorithm.annotation.Execute;
import org.n52.wps.algorithm.annotation.LiteralDataInput;
import org.n52.wps.server.AbstractAnnotatedAlgorithm;

/**
 *
 * @author tkunicki
 */
@Algorithm(
    version = "1.0.0",
    title = "WCS Subset",
    abstrakt="This service returns the subset of data that intersects a set of vector polygon features and a Web Coverage Service (WCS) data source. A GeoTIFF file will be returned.")
public class FeatureCoverageIntersectionAlgorithm extends AbstractAnnotatedAlgorithm {

    private FeatureCollection featureCollection;
    private URI datasetURI;
    private String datasetId;
    private boolean requireFullCoverage = true;

    private File output;

    @ComplexDataInput(
            identifier=GDPAlgorithmConstants.FEATURE_COLLECTION_IDENTIFIER,
            title=GDPAlgorithmConstants.FEATURE_COLLECTION_TITLE,
            abstrakt=GDPAlgorithmConstants.FEATURE_COLLECTION_ABSTRACT,
            binding=GMLStreamingFeatureCollectionBinding.class)
    public void setFeatureCollection(FeatureCollection featureCollection) {
        this.featureCollection = featureCollection;
    }

    @LiteralDataInput(
            identifier=GDPAlgorithmConstants.DATASET_URI_IDENTIFIER,
            title=GDPAlgorithmConstants.DATASET_URI_TITLE,
            abstrakt=GDPAlgorithmConstants.DATASET_URI_ABSTRACT + " The data web service must adhere to the Web Coverage Service standard.")
    public void setDatasetURI(URI datasetURI) {
        this.datasetURI = datasetURI;
    }

    @LiteralDataInput(
            identifier=GDPAlgorithmConstants.DATASET_ID_IDENTIFIER,
            title=GDPAlgorithmConstants.DATASET_ID_TITLE,
            abstrakt=GDPAlgorithmConstants.DATASET_ID_ABSTRACT)
    public void setDatasetId(String datasetId) {
        this.datasetId = datasetId;
    }
    
    @LiteralDataInput(
            identifier=GDPAlgorithmConstants.REQUIRE_FULL_COVERAGE_IDENTIFIER,
            title=GDPAlgorithmConstants.REQUIRE_FULL_COVERAGE_TITLE,
            abstrakt=GDPAlgorithmConstants.REQUIRE_FULL_COVERAGE_ABSTRACT,
            defaultValue="true")
    public void setRequireFullCoverage(boolean requireFullCoverage) {
        this.requireFullCoverage = requireFullCoverage;
    }

    @ComplexDataOutput(identifier="OUTPUT",
            title="Output File",
            abstrakt="A GeoTIFF file containing the requested data.",
            binding=GeoTIFFFileBinding.class)
    public File getOutput() {
        return output;
    }

    @Execute
    public void process() {
        output = WCSUtil.generateTIFFFile(datasetURI, datasetId, featureCollection.getBounds(), requireFullCoverage, AppConstant.WORK_LOCATION.getValue());
    }


}
