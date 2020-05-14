package eu.dnetlib.iis.wf.importer.infospace.converter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import java.time.Year;
import java.util.Collection;
import java.util.List;

import org.apache.log4j.Logger;
import org.junit.Test;

import com.google.common.collect.Lists;

import eu.dnetlib.dhp.schema.oaf.DataInfo;
import eu.dnetlib.dhp.schema.oaf.StructuredProperty;
import eu.dnetlib.iis.wf.importer.infospace.approver.FieldApprover;

/**
 * {@link MetadataConverterUtils} test class. 
 * 
 * @author mhorst
 *
 */
public class MetadataConverterUtilsTest {

    private Logger log = mock(Logger.class);
    
    @Test
    public void testExtractYearOrNullWithValidInput() throws Exception {
        assertEquals(Year.of(2020), MetadataConverterUtils.extractYearOrNull("2020-12-31", log));
    }
    
    @Test
    public void testExtractYearOrNullWithInvalidInput() throws Exception {
        assertNull(MetadataConverterUtils.extractYearOrNull("invalid", log));
        assertNull(MetadataConverterUtils.extractYearOrNull("20-20-01", log));
        assertNull(MetadataConverterUtils.extractYearOrNull("2020-01", log));
        assertNull(MetadataConverterUtils.extractYearOrNull("2020-01-1", log));
        assertNull(MetadataConverterUtils.extractYearOrNull("", log));
        assertNull(MetadataConverterUtils.extractYearOrNull(null, log));
    }
    
    @Test(expected = NullPointerException.class)
    public void testExtractValuesForNullApprover() throws Exception {
        // given
        Collection<StructuredProperty> source = Lists.newArrayList();
        String validValue = "someValue";
        source.add(generateStructuredProperty(validValue, null));
        
        // execute
        MetadataConverterUtils.extractValues(source, null);
    }
    
    @Test(expected = NullPointerException.class)
    public void testExtractValuesForNullSource() throws Exception {
        MetadataConverterUtils.extractValues(null, mock(FieldApprover.class));
    }
    
    @Test
    public void testExtractValuesForEmptySource() throws Exception {
        //execute
        List<String> results = MetadataConverterUtils.extractValues(Lists.newArrayList(), mock(FieldApprover.class));
        
        //assert
        assertNotNull(results);
        assertTrue(results.isEmpty());
    }
    
    @Test
    public void testExtractValues() throws Exception {
        // given
        @SuppressWarnings("serial")
        FieldApprover notNullDataInfoFieldApprover = new FieldApprover() {
            @Override
            public boolean approve(DataInfo dataInfo) {
                return dataInfo != null;
            }
        };
        DataInfo dataInfo = new DataInfo();
        Collection<StructuredProperty> source = Lists.newArrayList();
        String validValue = "someValue";
        source.add(generateStructuredProperty(validValue, dataInfo));
        source.add(generateStructuredProperty(null, dataInfo));
        source.add(generateStructuredProperty("toBeRejectedBecauseOfNullDataInfo", null));
        
        // execute
        List<String> results = MetadataConverterUtils.extractValues(source, notNullDataInfoFieldApprover);
        
        // assert
        assertNotNull(results);
        assertEquals(1, results.size());
        assertEquals(validValue, results.get(0));
    }

    private static StructuredProperty generateStructuredProperty(String value, DataInfo dataInfo) {
        StructuredProperty structProp = new StructuredProperty();
        structProp.setValue(value);
        structProp.setDataInfo(dataInfo);
        return structProp;
    }
    
}
