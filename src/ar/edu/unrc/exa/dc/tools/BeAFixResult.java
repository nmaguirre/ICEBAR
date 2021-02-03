package ar.edu.unrc.exa.dc.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;
import ar.edu.unrc.exa.dc.tools.BeAFixResult.BeAFixTest.TestType;
import ar.edu.unrc.exa.dc.util.Utils;

import static ar.edu.unrc.exa.dc.util.Utils.validateTestsFile;

public final class BeAFixResult {

    static final String TEST_SEPARATOR = "===TEST===\n";

    public enum ResultType {TESTS, CHECK, ERROR}

    public static final class BeAFixTest {

        public enum TestType {COUNTEREXAMPLE, UNTRUSTED_POSITIVE, UNTRUSTED_NEGATIVE, TRUSTED_POSITIVE, TRUSTED_NEGATIVE, INITIAL}
        private final TestType testType;
        private String command;
        private String predicate;
        private int index;

        public BeAFixTest(String test, TestType testType) {
            if (test == null || test.trim().isEmpty())
                throw new IllegalArgumentException("null or empty test");
            if (testType == null)
                throw new IllegalArgumentException("null test type");
            this.testType = testType;
            parseTest(test);
        }

        public String command() {
            return command;
        }

        public String predicate() {
            return predicate;
        }

        public TestType testType() {
            return testType;
        }

        public int getIndex() { return index; }

        private static final String PREDICATE_START_DELIMITER = "--TEST START\n";
        private static final String PREDICATE_END_DELIMITER = "--TEST FINISH\n";
        private void parseTest(final String test) {
            this.predicate = Utils.getBetweenStrings(test, PREDICATE_START_DELIMITER, PREDICATE_END_DELIMITER);
            if (predicate.isEmpty())
                throw new IllegalArgumentException("Predicate not found in:\n" + test);
            int runIdx = test.indexOf("run");
            if (runIdx < 0)
                throw new IllegalArgumentException("Command not found in:\n" + test);
            this.command = test.substring(runIdx, test.indexOf("\n", runIdx));
            String[] commandSegments = this.command.split(" ");
            if (commandSegments.length != 4)
                throw new IllegalArgumentException("Command was expected to have 4 words, but got " + commandSegments.length + " instead ( " + Arrays.toString(commandSegments) + ")");
            String commandsPredicate = commandSegments[1].trim();
            String indexRaw = commandsPredicate.replaceAll("\\D+","");
            this.index = Integer.parseInt(indexRaw);
        }

        @Override
        public int hashCode() {
            MessageDigest messageDigest;
            try {
                String expect = command().substring(command().indexOf("expect"));
                messageDigest = MessageDigest.getInstance("MD5");
                messageDigest.update(testType.name().getBytes());
                messageDigest.update(expect.getBytes());
                messageDigest.update(getPredicateBody().getBytes());
                byte[] digest = messageDigest.digest();
                return Arrays.hashCode(digest);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("This should not be happening!", e);
            }
        }

        @Override
        public boolean equals(Object other) {
            if (other == null)
                return false;
            if (this == other)
                return true;
            if (!(other instanceof BeAFixTest))
                return false;
            BeAFixTest otherAsTest = (BeAFixTest) other;
            if (!testType.equals(otherAsTest.testType))
                return false;
            return getPredicateBody().compareTo(((BeAFixTest) other).getPredicateBody()) == 0;
        }

        private String getPredicateBody() {
            int start = predicate.indexOf('{');
            if (start < 0)
                throw new IllegalStateException("There should be at least one { in the predicate\n" + predicate);
            return predicate.substring(start);
        }

        @Override
        public String toString() {
            String rep = "{\n\t" + testType.name();
            rep += "\n\tPredicate:\n" + predicate;
            rep += "\n\tCommand: " + command;
            rep += "\n}";
            return rep;
        }

    }

    private Path cetFile;
    private Path uptFile;
    private Path untFile;
    private Path tptFile;
    private Path tntFile;
    private String message;
    private boolean check;

    private Collection<BeAFixTest> ceTests;
    private Collection<BeAFixTest> uptTests;
    private Collection<BeAFixTest> untTests;
    private Collection<BeAFixTest> tptTests;
    private Collection<BeAFixTest> tntTests;
    private int maxIndex = -1;
    private ResultType resultType;

    //only for checks
    private int passingProperties = -1;
    private int totalProperties = -1;

    public int passingProperties() {
        return passingProperties;
    }

    private BeAFixResult() {}

    public static BeAFixResult tests() {
        BeAFixResult beAFixResult = new BeAFixResult();
        beAFixResult.resultType = ResultType.TESTS;
        return beAFixResult;
    }

    public static BeAFixResult error(String message) {
        BeAFixResult beAFixResult = new BeAFixResult();
        beAFixResult.resultType = ResultType.ERROR;
        beAFixResult.message(message);
        return beAFixResult;
    }

    public static BeAFixResult check(Path checkFile) {
        if (checkFile == null)
            throw new IllegalArgumentException("checkFile is null");
        if (!checkFile.toFile().exists())
            return error("No check file found at: " + checkFile.toString());
        return parseCheckFile(checkFile);
    }

    public boolean isCheck() { return this.resultType.equals(ResultType.CHECK); }

    public boolean checkResult() {
        if (!isCheck()) {
            throw new IllegalStateException("This is not a CHECK result");
        }
        return check;
    }

    public boolean error() {
        return this.resultType.equals(ResultType.ERROR);
    }

    public void message(String message) {
        this.message = message;
    }

    public String message() {
        return this.message;
    }

    public int getMaxIndex() {
        return maxIndex;
    }

    int getMaxIndexFrom(Collection<BeAFixTest> tests) {
        int max = 0;
        for (BeAFixTest test : tests) {
            if (test.getIndex() > max)
                max = test.getIndex();
        }
        return max;
    }

    public void counterexampleTests(Path cetFile) {
        this.cetFile = cetFile;
    }

    public void untrustedPositiveTests(Path uptFile) {
        this.uptFile = uptFile;
    }

    public void untrustedNegativeTests(Path untFile) {
        this.untFile = untFile;
    }

    public void trustedPositiveTests(Path tptFile) {
        this.tptFile = tptFile;
    }

    public void trustedNegativeTests(Path tntFile) {this.tntFile = tntFile;}

    public Collection<BeAFixTest> getCounterexampleTests() throws IOException {
        if (ceTests == null)
            ceTests = (isCheck() || error()) ? new LinkedList<>() : parseTestsFrom(cetFile, TestType.COUNTEREXAMPLE);
        maxIndex = Math.max(maxIndex, getMaxIndexFrom(ceTests));
        return ceTests;
    }

    public Collection<BeAFixTest> getUntrustedPositiveTests() throws IOException {
        if (uptTests == null)
            uptTests = (isCheck() || error()) ? new LinkedList<>() : parseTestsFrom(uptFile, TestType.UNTRUSTED_POSITIVE);
        maxIndex = Math.max(maxIndex, getMaxIndexFrom(uptTests));
        return uptTests;
    }

    public Collection<BeAFixTest> getUntrustedNegativeTests() throws IOException {
        if (untTests == null)
            untTests = (isCheck() || error()) ? new LinkedList<>() : parseTestsFrom(untFile, TestType.UNTRUSTED_NEGATIVE);
        maxIndex = Math.max(maxIndex, getMaxIndexFrom(untTests));
        return untTests;
    }

    public Collection<BeAFixTest> getTrustedPositiveTests() throws IOException {
        if (tptTests == null)
            tptTests = (isCheck() || error()) ? new LinkedList<>() : parseTestsFrom(tptFile, TestType.TRUSTED_POSITIVE);
        maxIndex = Math.max(maxIndex, getMaxIndexFrom(tptTests));
        return tptTests;
    }

    public Collection<BeAFixTest> getTrustedNegativeTests() throws IOException {
        if (tntTests == null)
            tntTests = (isCheck() || error()) ? new LinkedList<>() : parseTestsFrom(tntFile, TestType.TRUSTED_NEGATIVE);
        maxIndex = Math.max(maxIndex, getMaxIndexFrom(tntTests));
        return tntTests;
    }

    static Collection<BeAFixTest> parseTestsFrom(Path file, TestType testType) throws IOException {
        if (!validateTestsFile(file))
            throw new IllegalArgumentException("Invalid tests file: " + file.toString());
        Collection<BeAFixTest> tests = new LinkedList<>();
        String[] rawTests = Files.lines(file).collect(Collectors.joining("\n")).split(TEST_SEPARATOR);
        for (String rawTest : rawTests) {
            if (rawTest.trim().isEmpty())
                continue;
            BeAFixTest test = new BeAFixTest(rawTest, testType);
            tests.add(test);
        }
        return tests;
    }

    private static final String INVALID = "INVALID";
    private static final String VALID = "VALID";
    private static final String EXCEPTION = "EXCEPTION";
    private static BeAFixResult parseCheckFile(Path checkFile) {
        BeAFixResult beAFixResult = new BeAFixResult();
        try {
            List<String> checkLines = Files.lines(checkFile).collect(Collectors.toList());
            if (checkLines.isEmpty()) {
                return error("No lines found in check file: " + checkLines.toString());
            }
            String firstLine = checkLines.get(0);
            if (firstLine.startsWith(VALID)) {
                beAFixResult.resultType = ResultType.CHECK;
                beAFixResult.check = true;
                beAFixResult.message("Valid model (" + checkFile.toString().replace(".verification", ".als") + ")");
            } else if (firstLine.startsWith(INVALID)) {
                beAFixResult.resultType = ResultType.CHECK;
                beAFixResult.check = false;
                if (firstLine.contains("(")) {
                    String repairedAndTotalProperties = firstLine.substring(firstLine.indexOf("("));
                    repairedAndTotalProperties = repairedAndTotalProperties.replace("(", "").replace(")", "");
                    String[] values = repairedAndTotalProperties.split("/");
                    int repaired = Integer.parseInt(values[0].trim());
                    int total = Integer.parseInt(values[1].trim());
                    beAFixResult.passingProperties = repaired;
                    beAFixResult.totalProperties = total;
                }
                beAFixResult.message(
                                "Invalid model (" + checkFile.toString().replace(".verification", ".als") + ")" +
                                " passing properties: " + beAFixResult.passingProperties + "/" + beAFixResult.totalProperties
                );
            } else if (firstLine.startsWith(EXCEPTION)) {
                beAFixResult.resultType = ResultType.ERROR;
                StringBuilder exceptionMsg = new StringBuilder();
                for (int i = 1; i < checkLines.size(); i++) {
                    exceptionMsg.append(checkLines.get(i)).append("\n");
                }
                beAFixResult.message("Error validating model (" + checkFile.toString().replace(".verification", ".als") +  ")\n" + exceptionMsg);
            } else {
                return error("Invalid validation file (" + checkFile.toString() + ")" + "\n" + String.join("\n", checkLines));
            }
        } catch (IOException e) {
            e.printStackTrace();
            return error("Error while parsing check file (" + checkFile.toString() + ")" + "\n" + Utils.exceptionToString(e));
        }
        return beAFixResult;
    }

    @Override
    public String toString() {
        String rep = "{\n\t";
        switch (resultType) {
            case ERROR: {
                rep += "An error occurred!\n\tMessage: " + message + "\n}";
                break;
            }
            case TESTS: {
                String ceTests = testsToString(TestType.COUNTEREXAMPLE);
                String tpTests = testsToString(TestType.TRUSTED_POSITIVE);
                String upTests = testsToString(TestType.UNTRUSTED_POSITIVE);
                String tnTests = testsToString(TestType.TRUSTED_NEGATIVE);
                String unTests = testsToString(TestType.UNTRUSTED_NEGATIVE);
                rep += "Message: " + message;
                rep += "\n\tMax index for test batch: " + maxIndex;
                rep += "\n\tCounterexample tests:\n";
                rep += ceTests;
                rep += "\n\tPositive trusted tests:\n";
                rep += tpTests;
                rep += "\n\tPositive untrusted tests:\n";
                rep += upTests;
                rep += "\n\tNegative trusted tests:\n";
                rep += tnTests;
                rep += "\n\tNegative untrusted tests:\n";
                rep += unTests;
                rep += "}";
                break;
            }
            case CHECK: {
                rep += "CHECK " + (check?"SUCCEEDED":"FAILED") + "\n\tMessage: " + message + "\n}";
                break;
            }
        }
        return rep;
    }

    private String testsToString(TestType testType) {
        StringBuilder rep = new StringBuilder();
        try {
            Collection<BeAFixTest> tests = null;
            switch (testType) {
                case COUNTEREXAMPLE: {
                    tests = getCounterexampleTests();
                    break;
                }
                case UNTRUSTED_POSITIVE: {
                    tests = getUntrustedPositiveTests();
                    break;
                }
                case UNTRUSTED_NEGATIVE: {
                    tests = getUntrustedNegativeTests();
                    break;
                }
                case TRUSTED_POSITIVE: {
                    tests = getTrustedPositiveTests();
                    break;
                }
                case TRUSTED_NEGATIVE: {
                    tests = getTrustedNegativeTests();
                }
            }
            if (tests == null || tests.isEmpty())
                return "NO TESTS\n";
            for (BeAFixTest ceTest : tests) {
                rep.append(ceTest.toString()).append("\n");
            }
        } catch (IOException e) {
            rep.append("\tException while getting ").append(testType.name()).append(" tests:\n").append(Utils.exceptionToString(e));
        }
        return rep.toString();
    }

}
