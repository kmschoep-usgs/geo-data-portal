/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package gov.usgs.cida.gdp.utilities.bean;

import gov.usgs.cida.gdp.utilities.bean.Time.TimeBreakdown;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import static org.junit.Assert.*;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 *
 * @author isuftin
 */
public class TimeTest {

    public TimeTest() {
    }

    @BeforeClass
    public static void setUpClass() throws Exception {
    }

    @AfterClass
    public static void tearDownClass() throws Exception {
    }

    @Before
    public void setUp() {
    }

    @After
    public void tearDown() {
    }

    @Test
    public void testCreateTime() {
        Time result = new Time();
        assertNotNull(result);
    }

    @Test
    public void testCreateTimeWithDateRangeStringList() {
        List<String> input = new ArrayList<String>();
        input.add("2001-07-01 01:01:01");
        input.add("2002-07-15 01:01:01");
        Time result = null;
        try {
            result = new Time(input);
        } catch (ParseException ex) {
            Logger.getLogger(TimeTest.class.getName()).log(Level.SEVERE, null, ex);
            fail(ex.getMessage());
        }
        assertNotNull(result.getStarttime().toString());
        assertTrue(result.getStarttime().getMonth() == 7);
    }

    @Test
    public void testCreateTimeWithEmptyRangeStringList() {
        List<String> input = new ArrayList<String>();
        Time result = null;
        try {
            result = new Time(input);
        } catch (ParseException ex) {
            Logger.getLogger(TimeTest.class.getName()).log(Level.SEVERE, null, ex);
            fail(ex.getMessage());
        }
        assertNotNull(result.getStarttime().toString());
        assertTrue(result.getStarttime().getHour() == 0);
    }

    @Test
    public void testTimeBreakDownCreateWithCalendar() {
        Calendar cal = new GregorianCalendar();
        TimeBreakdown tbd = new Time.TimeBreakdown(cal);
        assertNotNull(tbd);
    }

    @Test
    public void testTimeBreakDownWithParseException() {
        try {
            TimeBreakdown tbd = new Time.TimeBreakdown("unparseable string");
        } catch (Exception ex) {
            assertEquals(ex.getClass(), ParseException.class);
        }
    }

    @Test
    public void testTimeSetGetTime() {
        Time target = new Time();
        target.setTime(Arrays.asList("1", "2"));
        assertEquals(target.getTime().size(), 2);
    }

    @Test
    public void testSetGetStartTime() {
        Calendar cal = new GregorianCalendar();
        TimeBreakdown tbd = new Time.TimeBreakdown(cal);
        Time target = new Time();
        target.setStarttime(tbd);
        assertNotNull(target.getStarttime());
    }

    @Test
    public void testSetGetEndTime() {
        Calendar cal = new GregorianCalendar();
        TimeBreakdown tbd = new Time.TimeBreakdown(cal);
        Time target = new Time();
        target.setEndtime(tbd);
        assertNotNull(target.getEndtime());
    }
     @Test
    public void testTimeBreakdownSetGetMonth() {
        TimeBreakdown target = new TimeBreakdown();
        target.setMonth(1);
        assertEquals(target.getMonth(),1);
    }

         @Test
    public void testTimeBreakdownSetGetDay() {
        TimeBreakdown target = new TimeBreakdown();
        target.setDay(1);
        assertEquals(target.getDay(),1);
    }

                @Test
    public void testTimeBreakdownSetGetYear() {
        TimeBreakdown target = new TimeBreakdown();
        target.setYear(1);
        assertEquals(target.getYear(),1);
    }

                                @Test
    public void testTimeBreakdownSetGetTimeZone() {
        TimeBreakdown target = new TimeBreakdown();
        target.setYear(1);
        assertEquals(target.getYear(),1);
    }
}