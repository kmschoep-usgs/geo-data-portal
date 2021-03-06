package gov.usgs.derivative;

import com.google.common.primitives.Floats;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ucar.ma2.DataType;
import ucar.nc2.dt.GridDatatype;

/**
 *
 * @author tkunicki
 */
public class GrowingSeasonLengthVisitor extends RunAboveThresholdVisitor {

    private final static Logger LOGGER = LoggerFactory.getLogger(GrowingSeasonLengthVisitor.class);
    
    public GrowingSeasonLengthVisitor(String outputDir) {
        super(outputDir);
    }
    
    @Override
    protected DerivativeValueDescriptor generateDerivativeValueDescriptor(List<GridDatatype> gridDatatypeList) {
        return new DerivativeValueDescriptor(
                "threshold", // name
                "air_temperature", // standard_name
                "degF",
                DataType.FLOAT,
                Floats.asList(new float[] { 32f } ),
                generateDerivativeOutputVariableName(gridDatatypeList, "growing_season_length"), // name
                "growing_season_length", // standard name TODO: ???
                "days", // units
                Short.valueOf((short)-1),
                DataType.SHORT);
    }
    
}
