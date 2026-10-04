package com.ccs.wiredroid.testrunner;

import com.ccs.wiredroid.maven.MavenTestRunner;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TestReportParserTest {

    @Test
    public void testParseAllPassOutputText() {
        String log = "Testing started at 12:00 ...\n"
                + "✓ CalculatorTest (45 ms)\n"
                + "  ✓ testAdd (10 ms)\n"
                + "  ✓ testSubtract (15 ms)\n"
                + "✓ 2 tests passed (2 tests total, 45 ms)\n"
                + "Process finished with exit code 0\n";

        TestReportParser.ParsedReport report = TestReportParser.fromOutputText(log);
        assertNotNull(report);
        assertEquals(2, report.totalTests);
        assertEquals(2, report.passedTests);
        assertEquals(0, report.failedTests);
        assertEquals(0, report.skippedTests);
        assertEquals(45, report.durationMs);
        assertTrue(report.isAllPassed());

        assertEquals(1, report.items.size());
        TestResultItem suite = report.items.get(0);
        assertEquals("CalculatorTest", suite.title);
        assertEquals(2, suite.children.size());
        assertEquals("testAdd", suite.children.get(0).title);
        assertEquals(TestResultItem.Status.PASSED, suite.children.get(0).status);
        assertEquals(10, suite.children.get(0).durationMs);
    }

    @Test
    public void testParseFailedOutputTextWithStackTraceAndLine() {
        String log = "Testing started at 12:00 ...\n"
                + "✗ CalculatorTest (com.example.CalculatorTest, 60 ms)\n"
                + "  ✓ testAdd (10 ms)\n"
                + "  ✗ testDivide (50 ms)\n"
                + "    java.lang.AssertionError: expected:<1> but was:<0>\n"
                + "    at com.example.CalculatorTest.testDivide(CalculatorTest.java:42)\n"
                + "    at org.junit.runners.model.FrameworkMethod.invokeExplosively(FrameworkMethod.java:47)\n"
                + "✗ Tests failed: 1 passed, 1 failed (2 tests total, 60 ms)\n"
                + "Process finished with exit code 1\n";

        TestReportParser.ParsedReport report = TestReportParser.fromOutputText(log);
        assertNotNull(report);
        assertEquals(2, report.totalTests);
        assertEquals(1, report.passedTests);
        assertEquals(1, report.failedTests);
        assertFalse(report.isAllPassed());

        assertEquals(1, report.items.size());
        TestResultItem suite = report.items.get(0);
        assertEquals(2, suite.children.size());

        TestResultItem failedMethod = suite.children.get(1);
        assertEquals("testDivide", failedMethod.title);
        assertEquals(TestResultItem.Status.FAILED, failedMethod.status);
        assertEquals("CalculatorTest.java", failedMethod.sourceFile);
        assertEquals(42, failedMethod.errorLine);
        assertNotNull(failedMethod.errorMessage);
        assertTrue(failedMethod.errorMessage.contains("AssertionError"));
    }

    @Test
    public void testExtractLocation() {
        String stackTrace = "java.lang.NullPointerException\n"
                + "\tat org.junit.Assert.fail(Assert.java:86)\n"
                + "\tat com.ccs.sample.MyServiceTest.testProcess(MyServiceTest.java:99)\n"
                + "\tat java.lang.reflect.Method.invoke(Method.java:508)\n";

        TestReportParser.SourceLocation loc = TestReportParser.extractLocation(stackTrace, "MyServiceTest");
        assertNotNull(loc);
        assertEquals("MyServiceTest.java", loc.fileName);
        assertEquals(99, loc.lineNumber);
    }

    @Test
    public void testFromMavenResults() {
        MavenTestRunner.TestClassResult classResult = new MavenTestRunner.TestClassResult(
                "com.demo.DemoTest", "DemoTest", 40, null
        );
        classResult.methodResults.add(new MavenTestRunner.TestMethodResult("testOne", "testOne()", true, false, 15, null, ""));

        AssertionError failure = new AssertionError("value mismatch");
        StackTraceElement[] st = new StackTraceElement[] {
                new StackTraceElement("com.demo.DemoTest", "testTwo", "DemoTest.java", 73),
                new StackTraceElement("org.junit.runners.ParentRunner", "run", "ParentRunner.java", 200)
        };
        failure.setStackTrace(st);

        classResult.methodResults.add(new MavenTestRunner.TestMethodResult("testTwo", "testTwo()", false, false, 25, failure, "failed details"));

        TestReportParser.ParsedReport report = TestReportParser.fromMavenResults(Collections.singletonList(classResult));
        assertNotNull(report);
        assertEquals(2, report.totalTests);
        assertEquals(1, report.passedTests);
        assertEquals(1, report.failedTests);
        assertEquals(0, report.skippedTests);
        assertEquals(40, report.durationMs);

        TestResultItem suite = report.items.get(0);
        assertEquals("DemoTest", suite.title);
        assertEquals(2, suite.children.size());

        TestResultItem m2 = suite.children.get(1);
        assertEquals("testTwo()", m2.title);
        assertEquals(TestResultItem.Status.FAILED, m2.status);
        assertEquals("DemoTest.java", m2.sourceFile);
        assertEquals(73, m2.errorLine);
        assertEquals("value mismatch", m2.errorMessage);
    }
}
