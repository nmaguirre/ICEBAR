package ar.edu.unrc.exa.dc.search;

import ar.edu.unrc.exa.dc.icebar.Report;
import ar.edu.unrc.exa.dc.tools.ARepair;
import ar.edu.unrc.exa.dc.tools.ARepair.ARepairResult;
import ar.edu.unrc.exa.dc.tools.BeAFix;
import ar.edu.unrc.exa.dc.tools.BeAFixResult;
import ar.edu.unrc.exa.dc.tools.BeAFixResult.BeAFixTest;
import ar.edu.unrc.exa.dc.tools.InitialTests;
import ar.edu.unrc.exa.dc.util.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static ar.edu.unrc.exa.dc.util.Utils.*;

public class IterativeCEBasedAlloyRepair {

    private static final Logger logger = Logger.getLogger(IterativeCEBasedAlloyRepair.class.getName());
    private static final Path logFile = Paths.get("Repair.log");

    static {
        try {
            // This block configure the logger with handler and formatter
            FileHandler fh = new FileHandler(logFile.toString());
            logger.addHandler(fh);
            SimpleFormatter formatter = new SimpleFormatter();
            fh.setFormatter(formatter);
        } catch (SecurityException | IOException e) {
            e.printStackTrace();
        }
    }

    public static final int LAPS_DEFAULT = 4;
    public static final String REPAIR_PROCESS_FILENAME = "icebar_search_graph";

    private final ARepair aRepair;
    private final BeAFix beAFix;
    private final Set<BeAFixTest> trustedCounterexampleTests;
    private final Path modelToRepair;
    private final Path oracle;
    private final int laps;
    private int totalTestsGenerated;
    private int arepairCalls;
    private int evaluatedCandidates;
    private int evaluatedCandidatesLeadingToNoFix;
    private int evaluatedCandidatesLeadingToSpurious;
    private TestHashes trustedTests;
    private TestHashes untrustedTests;

    public enum ICEBARSearch {
        DFS, BFS
    }

    private ICEBARSearch search = ICEBARSearch.DFS;
    public void setSearch(ICEBARSearch search) {
        this.search = search;
    }

    private boolean allowSecondarySearchSpace = false;
    public void allowSecondarySearchSpace(boolean allowSecondarySearchSpace) { this.allowSecondarySearchSpace = allowSecondarySearchSpace; }

    public enum ICEBARInitialTestsLocation {
        PREPEND, APPEND
    }

    private ICEBARInitialTestsLocation initialTestsLocation = ICEBARInitialTestsLocation.APPEND;
    public void setInitialTestsLocation(ICEBARInitialTestsLocation initialTestsLocation) { this.initialTestsLocation = initialTestsLocation; }

    private boolean allowFactsRelaxation = false;
    public void allowFactsRelaxation(boolean allowFactsRelaxation) { this.allowFactsRelaxation = allowFactsRelaxation; }

    private boolean globalTrustedTests = true;
    public void globalTrustedTests(boolean globalTrustedTests) { this.globalTrustedTests = globalTrustedTests; }

    private boolean forceAssertionGeneration = false;
    public void forceAssertionGeneration(boolean forceAssertionGeneration) { this.forceAssertionGeneration = forceAssertionGeneration; }

    private long timeout = 0;
    public void timeout(long timeout) { this.timeout = timeout; }

    private boolean keepGoingAfterARepairNPE = false;
    public void keepGoingAfterARepairNPE(boolean keepGoingAfterARepairNPE) { this.keepGoingAfterARepairNPE =keepGoingAfterARepairNPE; }

    private boolean keepGoingARepairNoFixAndOnlyTrustedTests = false;
    public void keepGoingARepairNoFixAndOnlyTrustedTests(boolean keepGoingARepairNoFixAndOnlyTrustedTests) { this.keepGoingARepairNoFixAndOnlyTrustedTests = keepGoingARepairNoFixAndOnlyTrustedTests; }


    private boolean restartForMoreUnseenTests = false;
    private boolean searchRestarted = false;
    public void restartForMoreUnseenTests(boolean restartForMoreUnseenTests) {this.restartForMoreUnseenTests = restartForMoreUnseenTests;}

    public IterativeCEBasedAlloyRepair(Path modelToRepair, Path oracle, ARepair aRepair, BeAFix beAFix, int laps) {
        if (!isValidPath(modelToRepair, Utils.PathCheck.ALS))
            throw new IllegalArgumentException("Invalid model to repair path (" + (modelToRepair==null?"NULL":modelToRepair.toString()) + ")");
        if (!isValidPath(oracle, Utils.PathCheck.ALS))
            throw new IllegalArgumentException("Invalid oracle path (" + (oracle==null?"NULL":oracle.toString()) + ")");
        if (aRepair == null)
            throw new IllegalArgumentException("null ARepair instance");
        if (beAFix == null)
            throw new IllegalArgumentException("null BeAFix instance");
        if (laps < 0)
            throw new IllegalArgumentException("Negative value for laps");
        this.aRepair = aRepair;
        this.aRepair.modelToRepair(modelToRepair);
        this.beAFix = beAFix;
        this.trustedCounterexampleTests = new HashSet<>();
        this.modelToRepair = modelToRepair;
        this.oracle = oracle;
        this.laps = laps;
        this.totalTestsGenerated = 0;
        this.arepairCalls = 0;
        this.evaluatedCandidates = 0;
        this.evaluatedCandidatesLeadingToNoFix = 0;
        this.evaluatedCandidatesLeadingToSpurious = 0;
    }

    public IterativeCEBasedAlloyRepair(Path modelToRepair, Path oracle, ARepair aRepair, BeAFix beAFix) {
        this(modelToRepair, oracle, aRepair, beAFix, LAPS_DEFAULT);
    }

    private InitialTests initialTests;
    public void setInitialTests(InitialTests initialTests) { this.initialTests = initialTests; }

    private boolean usePriorization = false;
    public void usePriorization(boolean usePriorization) {
        this.usePriorization = usePriorization;
    }

    private boolean printProcessGraph = false;
    public void printProcessGraph(boolean printProcessGraph) { this.printProcessGraph = printProcessGraph; }

    private boolean printAllUsedTests = false;
    public void printAllUsedTests(boolean printAllUsedTests) { this.printAllUsedTests = printAllUsedTests; }

    public boolean justRunningARepairOnce() {
        return laps == 0;
    }

    private RepairGraph repairGraph;
    public void printProcessGraph() {
        if (!printProcessGraph)
            throw new IllegalStateException("Repair process was run with 'printProcessGraph' set to false");
        if (repairGraph == null)
            throw new IllegalStateException("repairGraph is null");
        File dotFile = Paths.get(REPAIR_PROCESS_FILENAME + ".dot").toFile();
        if (dotFile.exists() && !dotFile.delete()) {
            logger.severe("Couldn't delete " + dotFile);
            return;
        }
        File svgFile = Paths.get(REPAIR_PROCESS_FILENAME + ".svg").toFile();
        if (svgFile.exists() && !svgFile.delete()) {
            logger.severe("Couldn't delete " + svgFile);
            return;
        }
        if (!repairGraph.generateDotFile(dotFile.toString()))
            logger.severe("Couldn't generate dot file");
        if (!repairGraph.generateSVG(dotFile.toString()))
            logger.severe("Couldn't generate svg file");
    }
    public Optional<FixCandidate> repair() throws IOException {
        //watches for different time process recording
        TimeCounter arepairTimeCounter = new TimeCounter();
        TimeCounter beafixTimeCounter = new TimeCounter();
        TimeCounter totalTime = new TimeCounter();
        //CEGAR process
        CandidateSpace searchSpace = null;
        CandidateSpace secondarySearchSpace = null;
        switch (search) {
            case DFS: {
                searchSpace = usePriorization?CandidateSpace.priorityStack():CandidateSpace.normalStack();
                if (allowSecondarySearchSpace)
                    secondarySearchSpace = usePriorization?CandidateSpace.priorityStack():CandidateSpace.normalStack();
                break;
            }
            case BFS: {
                searchSpace = usePriorization?CandidateSpace.priorityQueue():CandidateSpace.normalQueue();
                if (allowSecondarySearchSpace)
                    secondarySearchSpace = usePriorization?CandidateSpace.priorityQueue():CandidateSpace.normalQueue();
                break;
            }
        }
        FixCandidate originalCandidate = FixCandidate.initialCandidate(modelToRepair);
        searchSpace.push(originalCandidate);
        int maxReachedLap = 0;
        if (printProcessGraph)
            repairGraph = RepairGraph.createNewGraph(originalCandidate);
        if (printAllUsedTests) {
            trustedTests = new TestHashes();
            untrustedTests = new TestHashes();
        }
        totalTime.clockStart();
        while (!searchSpace.isEmpty() || allowSecondarySearchSpace) {
            if (searchSpace.isEmpty() && allowSecondarySearchSpace) {
                assert secondarySearchSpace != null;
                if (!secondarySearchSpace.isEmpty()) {
                    logger.info("Search space is empty, but secondary search space is enabled and not empty, redirecting one candidate from secondary to primary...");
                    searchSpace.push(secondarySearchSpace.pop());
                }
                else break;
            }
            FixCandidate current = searchSpace.pop();
            evaluatedCandidates++;
            maxReachedLap = Math.max(maxReachedLap, current.depth());
            logger.info("Repairing current candidate\n" + current);
            arepairTimeCounter.clockStart();
            ARepairResult aRepairResult = runARepairWithCurrentConfig(current);
            arepairTimeCounter.clockEnd();
            writeCandidateInfo(current, trustedCounterexampleTests, aRepairResult);
            if (printProcessGraph) {
                repairGraph.addARepairCall(current, this.trustedCounterexampleTests);
            }
            logger.info("ARepair finished\n" + aRepairResult.toString());
            if (aRepairResult.equals(ARepairResult.ERROR)) {
                logger.severe("ARepair call ended in error:\n" + aRepairResult.message());
                if (aRepairResult.nullPointerExceptionFound() && keepGoingAfterARepairNPE) {
                    logger.warning("ARepair ended with a NullPointerException but we are going to ignore that and hope for the best");
                    continue;
                }
                Report report = Report.arepairFailed(current, current.untrustedTests().size() + current.trustedTests().size() + trustedCounterexampleTests.size(), arepairTimeCounter, beafixTimeCounter, arepairCalls, generateTestsAndCandidateCounters());
                writeReport(report);
                return Optional.empty();
            }
            boolean repairFound = aRepairResult.hasRepair();
            boolean noTests = aRepairResult.equals(ARepairResult.NO_TESTS);
            boolean keepGoing = !repairFound && !noTests && keepGoingARepairNoFixAndOnlyTrustedTests && searchSpace.isEmpty() && !trustedCounterexampleTests.isEmpty() && current.untrustedTests().isEmpty();
            boolean checkAndGenerate = repairFound || noTests || keepGoing;
            if (printProcessGraph && !checkAndGenerate) {
                repairGraph.addNoFixFoundFrom(current);
            }
            if (checkAndGenerate) {
                boolean fromOriginal = aRepairResult.equals(ARepairResult.NO_TESTS) || keepGoing;
                FixCandidate repairCandidate = fromOriginal?current:FixCandidate.aRepairCheckCandidate(aRepairResult.repair(), current.depth());
                logger.info("Validating current candidate with BeAFix");
                beafixTimeCounter.clockStart();
                BeAFixResult beAFixCheckResult = runBeAFixWithCurrentConfig(repairCandidate, BeAFixMode.CHECK, false, false);
                beafixTimeCounter.clockEnd();
                logger.info( "BeAFix check finished\n" + beAFixCheckResult.toString());
                int repairedPropertiesForCurrent = beAFixCheckResult.passingProperties();
                if (beAFixCheckResult.error()) {
                    logger.severe("BeAFix check ended in error, ending search");
                    Report report = Report.beafixCheckFailed(current, current.untrustedTests().size() + current.trustedTests().size() + trustedCounterexampleTests.size(), beafixTimeCounter, arepairTimeCounter, arepairCalls, generateTestsAndCandidateCounters());
                    writeReport(report);
                    return Optional.empty();
                } else if (beAFixCheckResult.checkResult()) {
                    logger.info("BeAFix validated the repair, fix found");
                    Report report = Report.repairFound(current, current.untrustedTests().size() + current.trustedTests().size() + trustedCounterexampleTests.size(), beafixTimeCounter, arepairTimeCounter, arepairCalls, generateTestsAndCandidateCounters());
                    writeReport(report);
                    if (printProcessGraph) {
                        repairGraph.addRealFixFrom(current);
                    }
                    return Optional.of(repairCandidate);
                } else {
                    logger.info("BeAFix found the model to be invalid, generate tests and continue searching");
                    evaluatedCandidatesLeadingToSpurious++;
                    if (printProcessGraph) {
                        if (repairFound) {
                            repairGraph.addSpuriousFixFrom(current);
                            evaluatedCandidatesLeadingToNoFix++;
                        } else { //!repairFound
                            repairGraph.addFauxSpuriousFixFrom(current);
                        }
                    }
                    if (current.depth() < laps) {
                        if (timeout > 0) {
                            totalTime.updateTotalTime();
                            if (totalTime.toMinutes() >= timeout) {
                                logger.info("ICEBAR timeout (" + timeout + " minutes) reached");
                                Report report = Report.timeout(current, current.untrustedTests().size() + current.trustedTests().size() + trustedCounterexampleTests.size(), beafixTimeCounter, arepairTimeCounter, arepairCalls, generateTestsAndCandidateCounters());
                                writeReport(report);
                                if (printProcessGraph) {
                                    repairGraph.addTimeoutFrom(current);
                                }
                                return Optional.empty();
                            }
                        }

                        beafixTimeCounter.clockStart();
                        BeAFixResult beAFixResult = runBeAFixWithCurrentConfig(repairCandidate, BeAFixMode.TESTS, false, false);
                        beafixTimeCounter.clockEnd();
                        if (checkIfInvalidAndReportBeAFixResults(beAFixResult, current, beafixTimeCounter, arepairTimeCounter))
                            return Optional.empty();
                        List<BeAFixTest> counterexampleTests = beAFixResult.getCounterexampleTests();
                        List<BeAFixTest> counterexampleUntrustedTests = beAFixResult.getCounterExampleUntrustedTests();
                        List<BeAFixTest> predicateTests = beAFixResult.getPredicateTests();
                        List<BeAFixTest> relaxedPredicateTests = null;
                        List<BeAFixTest> relaxedAssertionsTests = null;
                        boolean testsGenerated = !(counterexampleTests.isEmpty() && counterexampleUntrustedTests.isEmpty() && predicateTests.isEmpty());
                        boolean testsGenerationLogged = false;
                        if (allowFactsRelaxation && ((counterexampleTests.isEmpty() && counterexampleUntrustedTests.isEmpty()) || allowSecondarySearchSpace) && predicateTests.isEmpty()) {
                            if (counterexampleTests.isEmpty() && counterexampleUntrustedTests.isEmpty())
                                logger.info("No tests available, generating with relaxed facts...");
                            else
                                logger.info("Counterexamples are available but secondary search space is enabled, generating with relaxed facts...");
                            beafixTimeCounter.clockStart();
                            beAFixResult = runBeAFixWithCurrentConfig(repairCandidate, BeAFixMode.TESTS, true, false);
                            beafixTimeCounter.clockEnd();
                            if (checkIfInvalidAndReportBeAFixResults(beAFixResult, current, beafixTimeCounter, arepairTimeCounter))
                                return Optional.empty();
                            relaxedPredicateTests = beAFixResult.getPredicateTests();
                            testsGenerated = !relaxedPredicateTests.isEmpty();
                            if (forceAssertionGeneration) {
                                logger.info("Generating with assertion forced test generation...");
                                beAFix.testsStartingIndex(Math.max(beAFix.testsStartingIndex(), beAFixResult.getMaxIndex()) + 1);
                                beafixTimeCounter.clockStart();
                                BeAFixResult beAFixResult_forcedAssertionTestGeneration = runBeAFixWithCurrentConfig(repairCandidate, BeAFixMode.TESTS, false, true);
                                beafixTimeCounter.clockEnd();
                                if (checkIfInvalidAndReportBeAFixResults(beAFixResult_forcedAssertionTestGeneration, current, beafixTimeCounter, arepairTimeCounter))
                                    return Optional.empty();
                                relaxedAssertionsTests = beAFixResult_forcedAssertionTestGeneration.getCounterExampleUntrustedTests();
                                testsGenerated = testsGenerated || !relaxedAssertionsTests.isEmpty();
                            }
                            if (printProcessGraph) {
                                Collection<BeAFixTest> localTests = new LinkedList<>();
                                if (relaxedAssertionsTests != null) localTests.addAll(relaxedAssertionsTests);
                                localTests.addAll(relaxedPredicateTests);
                                repairGraph.addGeneratedTestsFrom(current, Collections.emptyList(), localTests);
                                testsGenerationLogged = true;
                            }
                        }
                        if (printProcessGraph && !testsGenerationLogged) {
                            boolean trustedAsGlobal = globalTrustedTests || (current.untrustedTests().isEmpty() && current.trustedTests().isEmpty());
                            Collection<BeAFixTest> globalTests = trustedAsGlobal?counterexampleTests:Collections.emptyList();
                            Collection<BeAFixTest> localTests = new LinkedList<>();
                            if (!trustedAsGlobal)
                                localTests.addAll(counterexampleTests);
                            localTests.addAll(counterexampleUntrustedTests);
                            localTests.addAll(predicateTests);
                            repairGraph.addGeneratedTestsFrom(current, globalTests, localTests);
                        }
                        boolean trustedTestsAdded;
                        boolean addLocalTrustedTests;
                        boolean globalTestsAdded = false;
                        if (globalTrustedTests || (current.untrustedTests().isEmpty() && current.trustedTests().isEmpty())) {
                            trustedTestsAdded = this.trustedCounterexampleTests.addAll(counterexampleTests);
                            globalTestsAdded = trustedTestsAdded;
                            addLocalTrustedTests = false;
                        } else { //local trusted tests except from original
                            trustedTestsAdded = !counterexampleTests.isEmpty();
                            addLocalTrustedTests = true;
                        }
                        int newBranches = 0;
                        if (!counterexampleTests.isEmpty()) {
                            Set<BeAFixTest> localTrustedTests = new HashSet<>(current.trustedTests());
                            Set<BeAFixTest> localUntrustedTests = new HashSet<>(current.untrustedTests());
                            if (addLocalTrustedTests) {
                                localTrustedTests.addAll(counterexampleTests);
                            }
                            if (trustedTestsAdded) {
                                FixCandidate newCandidate = FixCandidate.descendant(modelToRepair, localUntrustedTests, localTrustedTests, current);
                                newCandidate.repairedProperties(repairedPropertiesForCurrent);
                                if (newCandidate.hasLocalTests() || globalTestsAdded) {
                                    searchSpace.push(newCandidate);
                                    newBranches = 1;
                                    if (printAllUsedTests) {
                                        counterexampleTests.forEach(trustedTests::add);
                                    }
                                } else {
                                    logger.warning("Candidate " + newCandidate.id() + " is invalid (no new tests could be added)");
                                }
                            }
                        }
                        boolean counterexamples = !counterexampleTests.isEmpty();
                        boolean untrustedCounterexamples = !counterexampleUntrustedTests.isEmpty();
                        boolean predicates = !predicateTests.isEmpty();
                        boolean relaxedPredicates = relaxedPredicateTests != null && !relaxedPredicateTests.isEmpty();
                        boolean relaxedAssertions = relaxedAssertionsTests != null && !relaxedAssertionsTests.isEmpty();
                        if ((!counterexamples || allowSecondarySearchSpace) && untrustedCounterexamples) {
                            if (!counterexamples) {
                                if ((newBranches = createBranches(current, counterexampleUntrustedTests, true, searchSpace, repairedPropertiesForCurrent)) == BRANCHING_ERROR) {
                                    logger.severe("Branching error!");
                                    return Optional.empty();
                                }
                            } else {
                                logger.info("Adding untrusted counterexamples candidates into secondary search space...");
                                if ((newBranches = createBranches(current, counterexampleUntrustedTests, true, secondarySearchSpace, repairedPropertiesForCurrent)) == BRANCHING_ERROR) {
                                    logger.severe("Branching error!");
                                    return Optional.empty();
                                }
                            }
                        }
                        if (((!counterexamples && !untrustedCounterexamples) || allowSecondarySearchSpace) && predicates) {
                            if (!counterexamples && !untrustedCounterexamples) {
                                if ((newBranches = createBranches(current, predicateTests, false, searchSpace, repairedPropertiesForCurrent)) == BRANCHING_ERROR) {
                                    logger.severe("Branching error!");
                                    return Optional.empty();
                                }
                            } else {
                                logger.info("Adding untrusted predicate candidates into secondary search space...");
                                if ((newBranches = createBranches(current, predicateTests, false, secondarySearchSpace, repairedPropertiesForCurrent)) == BRANCHING_ERROR) {
                                    logger.severe("Branching error!");
                                    return Optional.empty();
                                }
                            }
                        }
                        if (((!counterexamples && !untrustedCounterexamples && !predicates) || allowSecondarySearchSpace) && relaxedPredicates) {
                            if (!counterexamples && !untrustedCounterexamples && !predicates) {
                                if ((newBranches = createBranches(current, relaxedPredicateTests, false, searchSpace, repairedPropertiesForCurrent)) == BRANCHING_ERROR) {
                                    logger.severe("Branching error!");
                                    return Optional.empty();
                                }
                            } else {
                                logger.info("Adding untrusted relaxed candidates into secondary search space...");
                                if ((newBranches = createBranches(current, relaxedPredicateTests, false, secondarySearchSpace, repairedPropertiesForCurrent)) == BRANCHING_ERROR) {
                                    logger.severe("Branching error!");
                                    return Optional.empty();
                                }
                            }
                        }
                        if (((!counterexamples && !untrustedCounterexamples && !predicates && !relaxedPredicates) || allowSecondarySearchSpace) && relaxedAssertions) {
                            if (!counterexamples && !untrustedCounterexamples && !predicates && !relaxedPredicates) {
                                if ((newBranches = createBranches(current, relaxedAssertionsTests, false, searchSpace, repairedPropertiesForCurrent)) == BRANCHING_ERROR) {
                                    logger.severe("Branching error!");
                                    return Optional.empty();
                                }
                            } else {
                                logger.info("Adding untrusted relaxed assertions candidates into secondary search space...");
                                if ((newBranches = createBranches(current, relaxedAssertionsTests, false, secondarySearchSpace, repairedPropertiesForCurrent)) == BRANCHING_ERROR) {
                                    logger.severe("Branching error!");
                                    return Optional.empty();
                                }
                            }
                        }
                        if (printProcessGraph && !testsGenerated) {
                            repairGraph.addNoTestsFrom(current);
                        }
                        totalTestsGenerated += beAFixResult.generatedTests() + (relaxedPredicateTests==null?0:relaxedPredicateTests.size()) + (relaxedAssertionsTests==null?0:relaxedAssertionsTests.size());
                        logger.info("Total tests generated: " + totalTestsGenerated);
                        logger.info("Generated branches: " + newBranches);
                        beAFix.testsStartingIndex(Math.max(beAFix.testsStartingIndex(), beAFixResult.getMaxIndex()) + 1);
                    } else if (!justRunningARepairOnce()) {
                        logger.info("max laps reached (" + laps + "), ending branch");
                        if (printProcessGraph) {
                            repairGraph.addMaxLapFrom(current);
                        }
                    }
                }
            } else if (aRepairResult.hasMessage()) {
                logger.info("ARepair ended with the following message:\n" + aRepairResult.message());
            }
            if (justRunningARepairOnce() && !noTests && !repairFound) {
                logger.info("ICEBAR running ARepair once could not find a fix");
                Report report = Report.arepairOnceNoFixFound(totalTestsGenerated, beafixTimeCounter, arepairTimeCounter, arepairCalls, generateTestsAndCandidateCounters());
                writeReport(report);
                return Optional.empty();
            }
            if (justRunningARepairOnce() && !noTests && repairFound) {
                logger.info("ICEBAR running ARepair once found a spurious fix");
                Report report = Report.arepairOnceSpurious(totalTestsGenerated, beafixTimeCounter, arepairTimeCounter, arepairCalls, generateTestsAndCandidateCounters());
                writeReport(report);
                return Optional.empty();
            }
            if (restartForMoreUnseenTests && searchSpace.isEmpty()) {
                if (!searchRestarted || current != originalCandidate) {
                    searchRestarted = true;
                    searchSpace.push(originalCandidate);
                    logger.info("***Restarting search to allow for unseen tests to be used***");
                }
            }
        }
        logger.info("ICEBAR ended with no more candidates");
        Report report = Report.exhaustedSearchSpace(maxReachedLap, totalTestsGenerated, beafixTimeCounter, arepairTimeCounter, arepairCalls, generateTestsAndCandidateCounters());
        writeReport(report);
        return Optional.empty();
    }


    private static final int BRANCHING_ERROR = -1; //TODO: currently not in use
    private int createBranches(FixCandidate current, List<BeAFixTest> fromTests, boolean multipleBranches, CandidateSpace searchSpace, int repairedPropertiesForCurrent) {
        int branches = 0;
        for (List<BeAFixTest> combination : createBranchesCombinations(fromTests, multipleBranches)) {
            if (combination.isEmpty())
                continue;
            Set<BeAFixTest> localTrustedTests = new HashSet<>(current.trustedTests());
            Set<BeAFixTest> localUntrustedTests = new HashSet<>(current.untrustedTests());
            localUntrustedTests.addAll(combination);
            FixCandidate newCandidate = FixCandidate.descendant(modelToRepair, localUntrustedTests, localTrustedTests, current);
            newCandidate.repairedProperties(repairedPropertiesForCurrent);
            if (newCandidate.hasLocalTests()) {
                searchSpace.push(newCandidate);
                branches++;
                if (printAllUsedTests) {
                    combination.forEach(untrustedTests::add);
                }
            } else {
                logger.warning("Candidate " + newCandidate.id() + " is invalid (no new tests could be added)");
            }
        }
        return branches;
    }

    private List<List<BeAFixTest>> createBranchesCombinations(List<BeAFixTest> fromTests, boolean multipleBranches) {
        List<List<BeAFixTest>> allCombinations = new LinkedList<>();
        allCombinations.add(new LinkedList<>());
        for (BeAFixTest branchingTest : fromTests) {
            boolean oneBranch = false;
            if ((multipleBranches && !branchingTest.isMultipleBranch()) || (!multipleBranches && !branchingTest.isPositiveAndNegativeBranch())) {
                logger.severe((multipleBranches?"An untrusted counterexample test must have at least two alternate cases":"A predicate test must have a positive and negative branch") +
                        "\nThis could be caused by a repeated branch, could occur for unexpected instance tests, we will generate only one branch, but you should check the hashes log");
                oneBranch = true;
            }
            if (oneBranch) {
                for (List<BeAFixTest> combination : allCombinations) {
                    combination.add(branchingTest);
                }
            } else if (multipleBranches) {
                List<List<BeAFixTest>> newCombinations = new LinkedList<>();
                for (List<BeAFixTest> combination : allCombinations) {
                    for (BeAFixTest branch : branchingTest.getAlternateBranches()) {
                        List<BeAFixTest> newCombination = new LinkedList<>(combination);
                        newCombination.add(branch);
                        newCombinations.add(newCombination);
                    }
                }
                allCombinations.clear();
                allCombinations.addAll(newCombinations);
            } else {
                List<List<BeAFixTest>> newCombinations = new LinkedList<>();
                OneTypePair<BeAFixTest> positiveAndNegativeBranch = branchingTest.getPositiveAndNegativeBranches();
                BeAFixTest positive = positiveAndNegativeBranch.fst();
                BeAFixTest negative = positiveAndNegativeBranch.snd();
                for (List<BeAFixTest> combination : allCombinations) {
                    if (positive.isMultipleBranch()) {
                        for (BeAFixTest positiveBranch : positive.getAlternateBranches()) {
                            List<BeAFixTest> newCombination = new LinkedList<>(combination);
                            newCombination.add(positiveBranch);
                            newCombinations.add(newCombination);
                        }
                    } else {
                        List<BeAFixTest> newCombination = new LinkedList<>(combination);
                        newCombination.add(positive);
                        newCombinations.add(newCombination);
                    }
                    if (negative.isMultipleBranch()) {
                        for (BeAFixTest negativeBranch : negative.getAlternateBranches()) {
                            List<BeAFixTest> newCombination = new LinkedList<>(combination);
                            newCombination.add(negativeBranch);
                            newCombinations.add(newCombination);
                        }
                    } else {
                        List<BeAFixTest> newCombination = new LinkedList<>(combination);
                        newCombination.add(negative);
                        newCombinations.add(newCombination);
                    }
                }
                allCombinations.clear();
                allCombinations.addAll(newCombinations);
            }
        }
        return allCombinations;
    }

    private boolean checkIfInvalidAndReportBeAFixResults(BeAFixResult beAFixResult, FixCandidate current, TimeCounter beafixTimeCounter, TimeCounter arepairTimeCounter) throws IOException {
        if (!beAFixResult.error()) {
            String beafixMsg = "BeAFix finished\n";
            if (!beAFixResult.isCheck()) {
                beAFixResult.parseAllTests();
            }
            beafixMsg += beAFixResult + "\n";
            logger.info(beafixMsg);
            return false;
        } else {
            logger.severe("BeAFix test generation ended in error, ending search");
            Report report = Report.beafixGenFailed(current, totalTestsGenerated, beafixTimeCounter, arepairTimeCounter, arepairCalls, generateTestsAndCandidateCounters());
            writeReport(report);
            return true;
        }
    }

    private ARepairResult runARepairWithCurrentConfig(FixCandidate candidate) {
        if (!aRepair.cleanFixDirectory())
            logger.warning("There was a problem cleaning ARepair .hidden folder, will keep going (cross your fingers)");
        List<BeAFixTest> tests = new LinkedList<>(trustedCounterexampleTests);
        tests.addAll(candidate.untrustedTests());
        tests.addAll(candidate.trustedTests());
        if (tests.isEmpty() && (initialTests == null || initialTests.getInitialTests().isEmpty()))
            return ARepairResult.NO_TESTS;
        Path testsPath = Paths.get(modelToRepair.toAbsolutePath().toString().replace(".als", "_tests.als"));
        File testsFile = testsPath.toFile();
        if (testsFile.exists()) {
            if (!testsFile.delete()) {
                logger.severe("Couldn't delete tests file (" + testsFile + ")");
                ARepairResult error = ARepairResult.ERROR;
                error.message("Couldn't delete tests file (" + testsFile + ")");
                return error;
            }
        }
        int testCount;
        try {
            if (initialTests != null) {
                if (initialTestsLocation.equals(ICEBARInitialTestsLocation.PREPEND))
                    tests.addAll(0, initialTests.getInitialTests());
                else
                    tests.addAll(initialTests.getInitialTests());
            }
            testCount = generateTestsFile(tests, testsPath);
        } catch (IOException e) {
            logger.severe("An exception occurred while trying to generate tests file\n" + Utils.exceptionToString(e) + "\n");
            ARepairResult error = ARepairResult.ERROR;
            error.message(Utils.exceptionToString(e));
            return error;
        }
        logger.info("Running ARepair with " + testCount + " tests");
        writeTestsToLog(tests, logger);
        aRepair.testsPath(testsPath);
        logger.info("Executing ARepair:\n" + aRepair.aRepairCommandToString());
        ARepairResult aRepairResult = aRepair.run();
        arepairCalls++;
        return aRepairResult;
    }

    private enum BeAFixMode {TESTS, CHECK}

    private BeAFixResult runBeAFixWithCurrentConfig(FixCandidate candidate, BeAFixMode mode, boolean relaxedFacts, boolean forceAssertionGeneration) {
        try {
            if (!beAFix.cleanOutputDir()) {
                return BeAFixResult.error("Couldn't delete BeAFix output directory");
            }
        } catch (IOException e) {
            logger.severe("An exception occurred when trying to clean BeAFix output directory\n" + exceptionToString(e));
            return BeAFixResult.error("An exception occurred when trying to clean BeAFix output directory\n" + exceptionToString(e));
        }
        Path modelToCheckWithOraclePath = Paths.get(candidate.modelToRepair().toAbsolutePath().toString().replace(".als", "_withOracle.als"));
        File modelToCheckWithOracleFile = modelToCheckWithOraclePath.toFile();
        if (modelToCheckWithOracleFile.exists()) {
            if (!modelToCheckWithOracleFile.delete()) {
                logger.severe("Couldn't delete model with oracle file (" + (modelToCheckWithOracleFile) + ")");
                return BeAFixResult.error("Couldn't delete model with oracle file (" + (modelToCheckWithOracleFile) + ")");
            }
        }
        try {
            mergeFiles(candidate.modelToRepair(), oracle, modelToCheckWithOraclePath);
        } catch (IOException e) {
            logger.severe("An exception occurred while trying to generate model with oracle file\n" + Utils.exceptionToString(e) + "\n");
            return BeAFixResult.error(Utils.exceptionToString(e));
        }
        BeAFixResult beAFixResult = null;
        beAFix.pathToModel(modelToCheckWithOraclePath);
        beAFix.factsRelaxationGeneration(relaxedFacts);
        beAFix.forceAssertionTestsGeneration(forceAssertionGeneration);
        switch (mode) {
            case TESTS: {
                beAFixResult = beAFix.runTestGeneration();
                break;
            }
            case CHECK: {
                beAFixResult = beAFix.runModelCheck();
                break;
            }
        }
        return beAFixResult;
    }

    private Report.TestsAndCandidatesCounters generateTestsAndCandidateCounters() {
        int totalTests = printAllUsedTests?(trustedTests.count() + untrustedTests.count()):totalTestsGenerated;
        int trustedTestsUsed = printAllUsedTests?trustedTests.count():-1;
        int untrustedTestsUsed = printAllUsedTests? untrustedTests.count():-1;
        return new Report.TestsAndCandidatesCounters(totalTests, trustedTestsUsed, untrustedTestsUsed, evaluatedCandidates, evaluatedCandidatesLeadingToNoFix, evaluatedCandidatesLeadingToSpurious);
    }

}
