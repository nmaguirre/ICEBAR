package ar.edu.unrc.exa.dc.util;

import ar.edu.unrc.exa.dc.cegar.Report;
import ar.edu.unrc.exa.dc.tools.BeAFixResult.BeAFixTest;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;

public final class Utils {

    public static String exceptionToString(Exception e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        return sw.toString();
    }

    public enum PathCheck {
        DIR, ALS, JAR, FILE, EXISTS
    }

    public static boolean isValidPath(String pathToCheck, PathCheck check) {
        if (pathToCheck == null)
            return false;
        return isValidPath(Paths.get(pathToCheck), check);
    }

    public static boolean isValidPath(Path pathToCheck, PathCheck check) {
        if (pathToCheck == null)
            return false;
        if (!pathToCheck.toFile().exists())
            return false;
        switch (check) {
            case DIR: return pathToCheck.toFile().isDirectory();
            case ALS: return pathToCheck.toFile().isFile() && pathToCheck.toString().endsWith(".als");
            case JAR: return pathToCheck.toFile().isFile() && pathToCheck.toString().endsWith(".jar");
            case FILE: return pathToCheck.toFile().isFile();
            case EXISTS: return true;
        }
        return false;
    }

    public static void mergeFiles(Path a, Path b, Path result) throws IOException {
        if (!isValidPath(a, PathCheck.FILE))
            throw new IllegalArgumentException("first path is not valid (" + (a==null?"NULL":a.toString()) + ")");
        if (!isValidPath(a, PathCheck.FILE))
            throw new IllegalArgumentException("second path is not valid (" + (b==null?"NULL":b.toString()) + ")");
        if (result == null || result.toFile().exists())
            throw new IllegalArgumentException("result path is either null or points to an existing file (" + (result==null?"NULL":result.toString()) + ")");
        File rFile = result.toFile();
        if (!rFile.createNewFile())
            throw new Error("Couldn't create result file (" + (result.toString()) + ")");
        for (String aLine : Files.readAllLines(a)) {
            Files.write(result, (aLine + "\n").getBytes(), StandardOpenOption.APPEND);
        }
        Files.write(result, "\n".getBytes(), StandardOpenOption.APPEND);
        for (String bLine : Files.readAllLines(b)) {
            Files.write(result, (bLine + "\n").getBytes(), StandardOpenOption.APPEND);
        }
    }

    public static void generateTestsFile(Collection<BeAFixTest> tests, Path output) throws IOException {
        if (tests == null || tests.isEmpty())
            throw new IllegalArgumentException("null or empty tests");
        if (output == null || output.toFile().exists())
            throw new IllegalArgumentException("output path is either null or points to an existing file (" + (output==null?"NULL":output.toString()) + ")");
        File testsFile = output.toFile();
        if (!testsFile.createNewFile()) {
            throw new Error("Couldn't create tests file (" + (testsFile.toString()) + ")");
        }
        Files.write(output, "\n".getBytes(), StandardOpenOption.APPEND);
        for (BeAFixTest test : tests) {
            Files.write(output, ("--" + test.testType().toString() + "\n" + test.predicate() + "\n" + test.command() + "\n").getBytes(), StandardOpenOption.APPEND);
        }
    }

    /**
     * Get text between two strings. Passed limiting strings are not
     * included into result.
     *
     * @param text     Text to search in.
     * @param textFrom Text to start cutting from (exclusive).
     * @param textTo   Text to stop cutting at (exclusive).
     */
    public static String getBetweenStrings(
            String text,
            String textFrom,
            String textTo) {

        String result;

        // Cut the beginning of the text to not occasionally meet a
        // 'textTo' value in it:
        result =
                text.substring(
                        text.indexOf(textFrom) + textFrom.length()
                );

        // Cut the excessive ending of the text:
        result =
                result.substring(
                        0,
                        result.indexOf(textTo));

        return result;
    }

    /**
     * Deletes Folder with all of its content
     *
     * @param folder path to folder which should be deleted
     */
    public static void deleteFolderAndItsContent(final Path folder) throws IOException {
        Files.walkFileTree(folder, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!Files.isSymbolicLink(file))
                    Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                if (!Files.isSymbolicLink(dir))
                    Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public static void writeReport(Report report) throws IOException {
        String reportFileRaw = "cegar.info";
        Path reportFilePath = Paths.get(reportFileRaw);
        File reportFile = reportFilePath.toFile();
        if (reportFile.exists() && !reportFile.delete())
            throw new Error("Report file (" + reportFilePath.toString() + ") exists but couldn't be deleted");
        if (!reportFile.createNewFile()) {
            throw new Error("Couldn't create report file (" + reportFilePath.toString() + ")");
        }
        Files.write(reportFilePath, report.toString().getBytes(), StandardOpenOption.APPEND);
    }

    public static boolean validateTestsFile(Path tests) {
        if (tests == null)
            return false;
        if (!tests.toFile().exists())
            return false;
        if (!tests.toFile().isFile())
            return false;
        return tests.toString().endsWith(".tests");
    }

}
