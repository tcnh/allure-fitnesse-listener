package nl.hsac.fitnesse.junit.allure;

import fitnesse.junit.FitNessePageAnnotation;
import fitnesse.wiki.WikiPage;
import nl.hsac.fitnesse.fixture.Environment;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;
import ru.yandex.qatools.allure.Allure;
import ru.yandex.qatools.allure.config.AllureModelUtils;
import ru.yandex.qatools.allure.events.ClearStepStorageEvent;
import ru.yandex.qatools.allure.events.MakeAttachmentEvent;
import ru.yandex.qatools.allure.events.TestCaseFailureEvent;
import ru.yandex.qatools.allure.events.TestCaseFinishedEvent;
import ru.yandex.qatools.allure.events.TestCaseStartedEvent;
import ru.yandex.qatools.allure.events.TestSuiteFinishedEvent;
import ru.yandex.qatools.allure.events.TestSuiteStartedEvent;
import ru.yandex.qatools.allure.model.Label;
import ru.yandex.qatools.allure.utils.AllureResultsUtils;
import ru.yandex.qatools.allure.utils.AnnotationManager;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JUnit listener for Allure Framework. Based on default ru.yandex.qatools.allure.junit.AllureRunListener
 */

public class JUnitAllureFrameworkListener extends RunListener {
    private static final String SCREENSHOT_EXT = "png";
    private static final String PAGESOURCE_EXT = "html";
    private static final Pattern SCREENSHOT_PATTERN = Pattern.compile("href=\"([^\"]*." + SCREENSHOT_EXT + ")\"");
    private static final Pattern PAGESOURCE_PATTERN = Pattern.compile("href=\"([^\"]*." + PAGESOURCE_EXT + ")\"");
    private static final Pattern SPECIAL_PAGE_PATTERN = Pattern.compile(".*(\\.SuiteSetUp|\\.SuiteTearDown)$");
    private final Environment hsacEnvironment = Environment.getInstance();
    private final HashMap<String, String> suites;
    private final Label hostLabel;
    private final Allure allure;
    private boolean skipSpecialPages;

    public JUnitAllureFrameworkListener() {
        this.allure = Allure.LIFECYCLE;
        this.suites = new HashMap<>();
        String hostName = "unknown";
        try {
            hostName = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
        hostLabel = new Label();
        hostLabel.setName("host");
        hostLabel.setValue(hostName);
        skipSpecialPages = null != System.getProperty("skipSpecialPagesInAllure") ?
                Boolean.valueOf(System.getProperty("skipSpecialPagesInAllure")) : false;
    }

    private void testSuiteStarted(Description description) {
            String uid = this.generateSuiteUid(description.getDisplayName());
            String suiteName = description.getClassName();

            TestSuiteStartedEvent event = new TestSuiteStartedEvent(uid, suiteName);
            AnnotationManager am = new AnnotationManager(description.getAnnotations());
            am.update(event);
            event.withLabels(AllureModelUtils.createTestFrameworkLabel("FitNesse"));
            getAllure().fire(event);
        }

    @Override
    public void testStarted(Description description) {
        if (reportTestPage(description.getMethodName())) {
            FitNessePageAnnotation pageAnn = description.getAnnotation(FitNessePageAnnotation.class);
            if (pageAnn != null) {
                TestCaseStartedEvent event = new TestCaseStartedEvent(this.getSuiteUid(description), description.getMethodName());
                AnnotationManager am = new AnnotationManager(description.getAnnotations());
                am.update(event);

                this.fireClearStepStorage();
                getAllure().fire(event);

                WikiPage page = pageAnn.getWikiPage();
                addLabels(page);
            }
        }
    }

    @Override
    public void testFailure(Failure failure) {
        Description description = failure.getDescription();
        if (reportTestPage(description.getMethodName())) {
            if (description.isTest()) {
                Throwable exception = failure.getException();
                List<Pattern> patterns = new ArrayList<>();
                patterns.add(SCREENSHOT_PATTERN);
                patterns.add(PAGESOURCE_PATTERN);
                processAttachments(exception, patterns);

                this.fireTestCaseFailure(exception);
                this.recordTestResult(description);

            } else {
                this.startFakeTestCase(description);
                this.fireTestCaseFailure(failure.getException());
                this.finishFakeTestCase();
            }
        }
    }

    @Override
    public void testAssumptionFailure(Failure failure) {
        this.testFailure(failure);
    }

    @Override
    public void testFinished(Description description) {
        if (reportTestPage(description.getMethodName())) {
            String methodName = description.getMethodName();
            makeAttachment(fitnesseResult(methodName).getBytes(), "FitNesse Result page", "text/html");
            getAllure().fire(new TestCaseFinishedEvent());
        }
    }

    private void testSuiteFinished(String uid) {
        getAllure().fire(new TestSuiteFinishedEvent(uid));
    }

    @Override
    public void testRunFinished(Result result) throws IOException {

        for (String uid : this.getSuites().values()) {
            this.testSuiteFinished(uid);
        }
        copyFitNesseResults();
    }

    private String generateSuiteUid(String suiteName) {
        String uid = UUID.randomUUID().toString();
        synchronized (this.getSuites()) {
            this.getSuites().put(suiteName, uid);
            return uid;
        }
    }


    private String getSuiteUid(Description description) {
        String suiteName = description.getClassName();
        if (!this.getSuites().containsKey(suiteName)) {
            Description suiteDescription = Description.createSuiteDescription(description.getTestClass());
            this.testSuiteStarted(suiteDescription);
        }

        return this.getSuites().get(suiteName);
    }

    private void startFakeTestCase(Description description) {
        String uid = this.getSuiteUid(description);
        String name = description.isTest() ? description.getMethodName() : description.getClassName();
        TestCaseStartedEvent event = new TestCaseStartedEvent(uid, name);
        AnnotationManager am = new AnnotationManager(description.getAnnotations());
        am.update(event);
        this.fireClearStepStorage();
        getAllure().fire(event);
    }

    private void finishFakeTestCase() {
        getAllure().fire(new TestCaseFinishedEvent());
    }

    private void fireTestCaseFailure(Throwable throwable) {
        getAllure().fire((new TestCaseFailureEvent()).withThrowable(throwable));
    }

    private void fireClearStepStorage() {
        getAllure().fire(new ClearStepStorageEvent());
    }

    private Allure getAllure() {
        return this.allure;
    }

    private Map<String, String> getSuites() {
        return this.suites;
    }

    private void recordTestResult(Description description) {
        this.testFinished(description);
    }

    private void processAttachments(Throwable ex, List<Pattern> patterns) {
        if (null != ex.getMessage()) {
            for (Pattern pattern : patterns) {
                Matcher patternMatcher = pattern.matcher(ex.getMessage());
                if (patternMatcher.find()) {
                    String filePath = hsacEnvironment.getFitNesseRootDir() + "/" + patternMatcher.group(1);
                    String attName;
                    String type;
                    String ext = FilenameUtils.getExtension(Paths.get(filePath).toString());
                    if (ext.equalsIgnoreCase(SCREENSHOT_EXT)) {
                        attName = "Page Screenshot";
                        type = "image/png";
                    } else if (ext.equalsIgnoreCase(PAGESOURCE_EXT)) {
                        attName = "Page Source";
                        type = "text/html";
                    } else {
                        attName = "Attachment";
                        type = "text/html";
                    }
                    makeAttachment(fileToAttach(filePath), attName, type);
                }
            }
        }
    }

    private void makeAttachment(byte[] file, String attName, String type) {
        MakeAttachmentEvent ev = new MakeAttachmentEvent(file, attName, type);
        getAllure().fire(ev);
    }

    private byte[] fileToAttach(String filePath) {
        Path path = Paths.get(filePath);
        byte[] data;
        try {
            data = Files.readAllBytes(path);
        } catch (IOException var5) {
            System.err.println("file not found: " + path.toString());
            data = null;
        }
        return data;
    }

    private void copyFitNesseResults() throws IOException {
        File resultsSrc = new File(hsacEnvironment.getFitNesseRootDir());
        File resultsTarget = new File(AllureResultsUtils.getResultsDirectory(), "fitnesseResults");
        FileUtils.copyDirectory(resultsSrc, resultsTarget);
    }

    private String fitnesseResult(String test) {
        String style = "width: 99%; height: 99%; overflow: auto; border: 0px;";
        String iFrame = String.format("<iframe src=\"../fitnesseResults/%s.html\" style=\"%s\">", test, style);
        return String.format("<html><head><title>FitNesse Report</title></head><body>%s</body>", iFrame);
    }

    private void addLabels(WikiPage page) {
        List<Label> labels = createLabels(page);
        AllureSetLabelsEvent event = new AllureSetLabelsEvent(labels);
        getAllure().fire(event);
    }

    private List<Label> createLabels(WikiPage page) {
        List<Label> labels = new ArrayList<>();

        String suiteName = page.getParent().getName();
        Label featureLabel = new Label();
        featureLabel.setName("feature");
        featureLabel.setValue(suiteName);
        labels.add(featureLabel);

        for (String tag : getTags(page)) {
            tag = tag.trim();
            Label storyLabel = new Label();
            storyLabel.setName("story");
            storyLabel.setValue(tag);
            labels.add(storyLabel);
        }

        //For some reason, the host label no longer gets set when applying story labels..
        labels.add(hostLabel);
        return labels;
    }

    private String[] getTags(WikiPage page) {
        String[] tags = new String[0];
        String tagInfo = page.getData().getProperties().get("Suites");
        if (null != tagInfo) {
            tags = tagInfo.split(",");
        }
        return tags;
    }

    private boolean reportTestPage(String pageName) {
        return !skipSpecialPages || !SPECIAL_PAGE_PATTERN.matcher(pageName).matches();
    }
}
