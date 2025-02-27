package ar.edu.unrc.exa.dc.util;

import ar.edu.unrc.exa.dc.search.FixCandidate;
import ar.edu.unrc.exa.dc.tools.BeAFixResult;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static ar.edu.unrc.exa.dc.util.Utils.*;

public final class RepairGraph {

    public enum NODE_TYPE {
        ORIGINAL,
        NO_TESTS,
        FIX_SPURIOUS,
        FIX_FAUX_SPURIOUS,
        FIX_REAL,
        NO_FIX,
        MAX_LAP,
        TEST_GENERATION,
        TIMEOUT,
        AREPAIR_CALL
    }

    private static final String NO_TESTS_PREFIX = "NT";
    private static final String NO_FIX_PREFIX = "NF";
    private static final String REAL_FIX_PREFIX = "RF";
    private static final String FROM_ORIGINAL_PREFIX = "FO";
    private static final String FROM_SPURIOUS_FIX_PREFIX = "FS";
    private static final String FROM_FAUX_SPURIOUS_PREFIX = "FF";
    private static final String MAX_LAP_PREFIX = "ML";
    private static final String TESTS_PREFIX = "TG";
    private static final String TIMEOUT_PREFIX = "TO";
    private static final String AREPAIR_PREFIX = "AR";

    private static boolean storeTests = false;
    public static void storeTests(boolean storeTests) { RepairGraph.storeTests = storeTests; }
    public static boolean storeTests() { return storeTests; }

    private static final Path GRAPHS_FOLDER_DEFAULT = Paths.get("");
    private static Path graphsFolder = GRAPHS_FOLDER_DEFAULT;
    public static void graphsFolder(Path graphsFolder) { RepairGraph.graphsFolder = graphsFolder; }
    public static Path graphsFolder() { return RepairGraph.graphsFolder; }

    private static boolean cleanGraphsFolder = false;
    public static void cleanGraphsFolder(boolean cleanGraphsFolder) { RepairGraph.cleanGraphsFolder = cleanGraphsFolder; }
    public static boolean cleanGraphsFolder() { return RepairGraph.cleanGraphsFolder; }

    private static final String TESTS_FOLDER = "tests";

    private final Node root;

    public static RepairGraph createNewGraph(FixCandidate from) {
        return new RepairGraph(from);
    }

    public void addARepairCall(FixCandidate candidate, Collection<BeAFixResult.BeAFixTest> globalTests) {
        String fromIdRaw = candidate.parent() == null?candidate.id():candidate.parent().id();
        String fromIdOriginal = convertCandidateIdToNodeId(fromIdRaw, NODE_TYPE.ORIGINAL);
        String fromIdTestGeneration = convertCandidateIdToNodeId(fromIdRaw, NODE_TYPE.TEST_GENERATION);
        String arepairCallId = convertCandidateIdToNodeId(candidate.id(), NODE_TYPE.AREPAIR_CALL);
        Collection<BeAFixResult.BeAFixTest> testsLocal = new LinkedList<>();
        testsLocal.addAll(candidate.untrustedTests());
        testsLocal.addAll(candidate.trustedTests());
        int global = globalTests==null?0:countNonBranching(globalTests);
        int positive = countPositive(testsLocal);
        int negative = countNegative(testsLocal);
        int local = testsLocal.size();
        String extraInformation = "GT(" + global + ") LT(" + local + ")[+" + positive + ",-" + negative + "]";
        if (root.searchNode(fromIdTestGeneration).isPresent())
            searchAndAddDescendant(fromIdTestGeneration, arepairCallId, NODE_TYPE.AREPAIR_CALL, extraInformation);
        else
            searchAndAddDescendant(fromIdOriginal, arepairCallId, NODE_TYPE.AREPAIR_CALL, extraInformation);
        if (storeTests()) {
            Collection<BeAFixResult.BeAFixTest> allTests = new LinkedList<>();
            if (globalTests != null)
                allTests.addAll(globalTests);
            allTests.addAll(testsLocal);
            Path fullPath = getFullTestFilePathFromCandidate(candidate);
            try {
                if (allTests.isEmpty())
                    writeToFile(fullPath, true, "--NO TESTS");
                else
                    generateTestsFile(allTests, fullPath);
            } catch (IOException e) {
                throw new IllegalStateException("Something went wrong while writing test file (" + fullPath + ")", e);
            }
        }
    }

    public void addRealFixFrom(FixCandidate realFix) {
        addFix(realFix, NODE_TYPE.FIX_REAL);
    }

    public void addSpuriousFixFrom(FixCandidate spuriousFix) {
        addFix(spuriousFix, NODE_TYPE.FIX_SPURIOUS);
    }

    public void addFauxSpuriousFixFrom(FixCandidate fauxSpuriousFix) {
        addFix(fauxSpuriousFix, NODE_TYPE.FIX_FAUX_SPURIOUS);
    }

    public void addNoFixFoundFrom(FixCandidate current) {
        addFix(current, NODE_TYPE.NO_FIX);
    }

    private void addFix(FixCandidate fix, NODE_TYPE fixType) {
        String fromId = convertCandidateIdToNodeId(fix.id(), NODE_TYPE.AREPAIR_CALL);
        String fixId = convertCandidateIdToNodeId(fix.id(), fixType);
        String extraInformation = "local tests: " + fix.trustedTests().size() + "T" + "|" + fix.untrustedTests().size() + "U";
        searchAndAddDescendant(fromId, fixId, fixType, extraInformation);
    }

    public void addGeneratedTestsFrom(FixCandidate from, Collection<BeAFixResult.BeAFixTest> testsGlobal, Collection<BeAFixResult.BeAFixTest> testsLocal) {
        String fromId = convertCandidateIdToNodeId(from.id(), NODE_TYPE.FIX_SPURIOUS);
        String fromIdFauxSpurious = convertCandidateIdToNodeId(from.id(), NODE_TYPE.FIX_FAUX_SPURIOUS);
        String testGenerationId = convertCandidateIdToNodeId(from.id(), NODE_TYPE.TEST_GENERATION);
        int global = testsGlobal==null?0:countNonBranching(testsGlobal);
        int positive = countPositive(testsLocal);
        int negative = countNegative(testsLocal);
        int local = testsLocal.size();
        String extraInformation = "GT(" + global + ") LT(" + local + ")[+" + positive + ",-" + negative + "]";
        if (root.searchNode(fromIdFauxSpurious).isPresent())
            searchAndAddDescendant(fromIdFauxSpurious, testGenerationId, NODE_TYPE.TEST_GENERATION, extraInformation);
        else
            searchAndAddDescendant(fromId, testGenerationId, NODE_TYPE.TEST_GENERATION, extraInformation);
    }

    public void addNoTestsFrom(FixCandidate from) {
        String fromId = convertCandidateIdToNodeId(from.id(), NODE_TYPE.TEST_GENERATION);
        String noTestsId = convertCandidateIdToNodeId(generateRandomName(), NODE_TYPE.NO_TESTS);
        searchAndAddDescendant(fromId, noTestsId, NODE_TYPE.NO_TESTS);
    }

    public void addMaxLapFrom(FixCandidate from) {
        String fromId = convertCandidateIdToNodeId(from.id(), NODE_TYPE.FIX_SPURIOUS);
        String fromFauxSpuriousId = convertCandidateIdToNodeId(from.id(), NODE_TYPE.FIX_FAUX_SPURIOUS);
        String maxLapId = convertCandidateIdToNodeId(generateRandomName(), NODE_TYPE.MAX_LAP);
        if (root.searchNode(fromFauxSpuriousId).isPresent())
            searchAndAddDescendant(fromFauxSpuriousId, maxLapId, NODE_TYPE.MAX_LAP);
        else
            searchAndAddDescendant(fromId, maxLapId, NODE_TYPE.MAX_LAP);
    }

    public void addTimeoutFrom(FixCandidate from) {
        String fromId = convertCandidateIdToNodeId(from.id(), NODE_TYPE.FIX_SPURIOUS);
        String fromFauxSpuriousId = convertCandidateIdToNodeId(from.id(), NODE_TYPE.FIX_FAUX_SPURIOUS);
        String timeoutId = convertCandidateIdToNodeId(generateRandomName(), NODE_TYPE.TIMEOUT);
        if (root.searchNode(fromFauxSpuriousId).isPresent())
            searchAndAddDescendant(fromFauxSpuriousId, timeoutId, NODE_TYPE.TIMEOUT);
        else
            searchAndAddDescendant(fromId, timeoutId, NODE_TYPE.TIMEOUT);
    }

    private static final String GENERAL_NODE = "node [fontsize = 10 style=filled];";
    private static final String LABEL_SUB_LABEL_COMPONENT = "label=<LABEL<BR />\n" + "<FONT POINT-SIZE=\"10\">EXTRAINFO</FONT>>";
    private static final String LABEL_ONLY_COMPONENT = "label=LABEL";
    private static final String URL_COMPONENT = "URL=\"file:PATH\"";
    private static final String ORIGINAL_NODE = "[shape = egg fillcolor = cornflowerblue LABEL];";
    private static final String REAL_FIX_NODE = "[shape = house fillcolor = chartreuse4 LABEL];";
    private static final String SPURIOUS_FIX_NODE = "[shape = polygon fillcolor = darkgoldenrod2 LABEL];";
    private static final String FAUX_SPURIOUS_FIX_NODE = "[shape = polygon fillcolor = darkgoldenrod LABEL];";
    private static final String NO_FIX_NODE = "[shape = triangle fillcolor = indianred LABEL];";
    private static final String NO_TESTS_NODE = "[shape = triangle fillcolor = ivory4 LABEL];";
    private static final String MAX_LAP_NODE = "[shape = triangle fillcolor = black LABEL];";
    private static final String TEST_GENERATION_NODE = "[shape = diamond fillcolor = cyan LABEL];";
    private static final String AREPAIR_CALL_NODE = "[shape = diamond fillcolor = yellow LABEL]";
    private static final String AREPAIR_CALL_NODE_WITH_URL = "[shape = diamond fillcolor = yellow LABEL URL]";
    private static final String TIMEOUT_NODE = "[shape = triangle fillcolor = indigo LABEL]";

    public boolean generateDotFile(String file) {
        Path pfile = fileNameToFullPath(file);
        if (Files.exists(pfile)) { //file already exists
            return false;
        }
        Path parent = pfile.toAbsolutePath().getParent();
        try {
            Files.createDirectories(parent);
        } catch (IOException e1) {
            e1.printStackTrace();
            return false;
        }
        try {
            if (!pfile.toFile().createNewFile())
                return false;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        String indent = "\t\t";
        StringBuilder sb = new StringBuilder();
        sb.append("digraph ICEBAR_PROCESS_GRAPH {\n");
        sb.append(indent).append("rankdir=TD;\n");
        sb.append(indent).append("ratio=\"auto\";\n");
        sb.append(indent).append("ranksep=\"1.5 equally\";\n");
        sb.append(indent).append(GENERAL_NODE);
        sb.append(indent).append(generateNodeStatement(root, ORIGINAL_NODE, LABEL_ONLY_COMPONENT)).append("\n");
        Collection<Node> realFixes = getRealFixNodes();
        if (!realFixes.isEmpty()) {
            sb.append(generateNodeStatements(realFixes, REAL_FIX_NODE, LABEL_SUB_LABEL_COMPONENT, indent));
        }
        Collection<Node> spuriousFixes = getSpuriousFixNodes();
        if (!spuriousFixes.isEmpty()) {
            sb.append(generateNodeStatements(spuriousFixes, SPURIOUS_FIX_NODE, LABEL_SUB_LABEL_COMPONENT, indent));
        }
        Collection<Node> fauxSpuriousFixes = getFauxSpuriousFixNodes();
        if (!fauxSpuriousFixes.isEmpty()) {
            sb.append(generateNodeStatements(fauxSpuriousFixes, FAUX_SPURIOUS_FIX_NODE, LABEL_SUB_LABEL_COMPONENT, indent));
        }
        Collection<Node> noFixes = getNoFixNodes();
        if (!noFixes.isEmpty()) {
            sb.append(generateNodeStatements(noFixes, NO_FIX_NODE, LABEL_SUB_LABEL_COMPONENT, indent));
        }
        Collection<Node> noTests = getNoTestsNodes();
        if (!noTests.isEmpty()) {
            sb.append(generateNodeStatements(noTests, NO_TESTS_NODE, LABEL_ONLY_COMPONENT, indent));
        }
        Collection<Node> maxLaps = getMaxLapNodes();
        if (!maxLaps.isEmpty()) {
            sb.append(generateNodeStatements(maxLaps, MAX_LAP_NODE, LABEL_ONLY_COMPONENT, indent));
        }
        Collection<Node> testGenerationNodes = getTestGenerationNodes();
        if (!testGenerationNodes.isEmpty()) {
            sb.append(generateNodeStatements(testGenerationNodes, TEST_GENERATION_NODE, LABEL_SUB_LABEL_COMPONENT, indent));
        }
        Collection<Node> arepairCallNodes = getARepairCallNodes();
        if (!arepairCallNodes.isEmpty()) {
            sb.append(generateNodeStatements(arepairCallNodes, storeTests()?AREPAIR_CALL_NODE_WITH_URL:AREPAIR_CALL_NODE, LABEL_SUB_LABEL_COMPONENT, indent));
        }
        Collection<Node> timeoutNodes = getTimeoutNodes();
        if (!timeoutNodes.isEmpty()) {
            sb.append(generateNodeStatements(timeoutNodes, TIMEOUT_NODE, LABEL_ONLY_COMPONENT, indent));
        }
        sb.append("\n");
        Queue<Node> queue = new LinkedList<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            Node current = queue.remove();
            for (Node descendant : current.getDescendants()) {
                queue.add(descendant);
                sb.append(indent).append(current).append(" -> ").append(descendant).append(";\n");
            }
        }
        sb.append("}\n");
        try {
            Files.write(pfile, sb.toString().getBytes());
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    private String generateNodeStatements(Collection<Node> nodes, String nodeTemplate, String labelTemplate, String indent) {
        StringBuilder sb = new StringBuilder();
        for (Node node : nodes) {
            sb.append(indent).append(generateNodeStatement(node, nodeTemplate, labelTemplate)).append("\n");
        }
        return sb.toString();
    }

    private String generateNodeStatement(Node node, String nodeTemplate, String labelTemplate) {
        String labelSection = labelTemplate.replace("LABEL", node.toString());
        if (node.extraInformation() != null && !node.extraInformation().isEmpty()) {
            labelSection = labelSection.replace("EXTRAINFO", node.extraInformation());
        }
        if (nodeTemplate.compareTo(AREPAIR_CALL_NODE_WITH_URL) == 0) {
            String urlComponent = URL_COMPONENT.replace("PATH", getFullTestFilePathFromNodeId(node.id).toAbsolutePath().toString());
            nodeTemplate = nodeTemplate.replace("URL", urlComponent);
        }
        String nodeProperties = nodeTemplate.replace("LABEL", labelSection);
        return node + " " + nodeProperties;
    }

    public boolean generateSVG(String file) {
        Path dotFile = fileNameToFullPath(file);
        Path svgFile = fileNameToFullPath(file.replace(".dot", ".svg"));
        if (Files.exists(svgFile)) {
            return false;
        }
        String[] args = getGraphGenerationCommand(dotFile.toString());
        ProcessBuilder pb = new ProcessBuilder(args);
        File errorLog = new File("error.log");
        pb.redirectError(ProcessBuilder.Redirect.appendTo(errorLog));
        Process p;
        try {
            p = pb.start();
            int exitCode = p.waitFor();
            return exitCode == 0;
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            return false;
        }
    }

    private RepairGraph(FixCandidate candidate) {
        if (candidate == null)
            throw new IllegalArgumentException("candidate can't be null");
        if (!graphsFolder().equals(GRAPHS_FOLDER_DEFAULT) && cleanGraphsFolder && Utils.isValidPath(graphsFolder, PathCheck.DIR)) {
            try {
                deleteFolderAndItsContent(graphsFolder);
            } catch (IOException e) {
                throw new IllegalStateException("An error occurred while trying to clean graphs folder");
            }
        }
        if (!graphsFolder().equals(GRAPHS_FOLDER_DEFAULT)) {
            if (!Utils.checkAndCreateDirectory(graphsFolder())) {
                throw new IllegalStateException("Graphs folder (" + graphsFolder() + ") either exists and it's not empty or it couldn't be created");
            }
        }
        if (storeTests()) {
            Path testsFolder = Paths.get(graphsFolder().toString(), TESTS_FOLDER);
            if (!Utils.checkAndCreateDirectory(testsFolder)) {
                throw new IllegalStateException("Tests folder (" + testsFolder + ") either exists and it's not empty or it couldn't be created");
            }
        }
        this.root = Node.leaf(NODE_TYPE.ORIGINAL, convertCandidateIdToNodeId(candidate.id(), NODE_TYPE.ORIGINAL));
    }

    private String convertCandidateIdToNodeId(String originalId, NODE_TYPE nodeType) {
        switch (nodeType) {
            case ORIGINAL: return FROM_ORIGINAL_PREFIX + originalId;
            case FIX_REAL: return REAL_FIX_PREFIX + originalId;
            case FIX_FAUX_SPURIOUS: return FROM_FAUX_SPURIOUS_PREFIX + originalId;
            case FIX_SPURIOUS: return FROM_SPURIOUS_FIX_PREFIX + originalId;
            case NO_FIX: return NO_FIX_PREFIX + originalId;
            case NO_TESTS: return NO_TESTS_PREFIX + originalId;
            case MAX_LAP: return MAX_LAP_PREFIX + originalId;
            case TEST_GENERATION: return TESTS_PREFIX + originalId;
            case AREPAIR_CALL: return AREPAIR_PREFIX + originalId;
            case TIMEOUT: return TIMEOUT_PREFIX + originalId;
        }
        return "N/A";
    }

    private void searchAndAddDescendant(String fromId, String descendantId, NODE_TYPE nodeType) {
        searchAndAddDescendant(fromId, descendantId, nodeType, "");
    }

    private void searchAndAddDescendant(String fromId, String descendantId, NODE_TYPE nodeType, String extraInformation) {
        Optional<Node> fromNode = root.searchNode(fromId);
        if (fromNode.isPresent()) {
            fromNode.get().addDescendant(nodeType, descendantId, extraInformation);
        } else {
            throw new IllegalStateException("No id found for 'from' candidate");
        }
    }

    private Collection<Node> getAllNodesOfType(NODE_TYPE nodeType) {
        Collection<Node> nodes = new LinkedList<>();
        Queue<Node> queue = new LinkedList<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            Node current = queue.remove();
            if (current.nodeType.equals(nodeType))
                nodes.add(current);
            queue.addAll(current.getDescendants());
        }
        return nodes;
    }

    private Collection<Node> getRealFixNodes() { return getAllNodesOfType(NODE_TYPE.FIX_REAL); }

    private Collection<Node> getSpuriousFixNodes() { return getAllNodesOfType(NODE_TYPE.FIX_SPURIOUS); }

    private Collection<Node> getFauxSpuriousFixNodes() { return getAllNodesOfType(NODE_TYPE.FIX_FAUX_SPURIOUS); }

    private Collection<Node> getNoFixNodes() { return getAllNodesOfType(NODE_TYPE.NO_FIX); }

    private Collection<Node> getNoTestsNodes() { return getAllNodesOfType(NODE_TYPE.NO_TESTS); }

    private Collection<Node> getMaxLapNodes()  { return getAllNodesOfType(NODE_TYPE.MAX_LAP); }

    private Collection<Node> getTestGenerationNodes() { return getAllNodesOfType(NODE_TYPE.TEST_GENERATION); }

    private Collection<Node> getARepairCallNodes() { return getAllNodesOfType(NODE_TYPE.AREPAIR_CALL); }

    private Collection<Node> getTimeoutNodes() { return getAllNodesOfType(NODE_TYPE.TIMEOUT); }

    private String[] getGraphGenerationCommand(String file) {
        String[] args = new String[4];
        args[0] = "dot";
        args[1] = "-Tsvg";
        args[2] = "-o"+file.replace(".dot", ".svg");
        args[3] = file;
        return args;
    }

    private int countPositive(Collection<BeAFixResult.BeAFixTest> tests) {
        return count(tests, true);
    }

    private int countNegative(Collection<BeAFixResult.BeAFixTest> tests) {
        return count(tests, false);
    }

    private int countNonBranching(Collection<BeAFixResult.BeAFixTest> tests) {
        int count = 0;
        for (BeAFixResult.BeAFixTest test : tests) {
            count += countNonBranching(test);
        }
        return count;
    }

    private int countNonBranching(BeAFixResult.BeAFixTest test) {
        int count = 0;
        if (!test.isPositiveAndNegativeBranch() && test.isBranchedTest()) {
            count += test.getAlternateBranches().stream().map(this::countNonBranching).reduce(0, Integer::sum);
        } else if (!test.isBranchedTest() && !test.isPositiveBranch() && !test.isNegativeBranch()) {
            count++;
            if (test.isRelated())
                count++;
        }
        return count;
    }

    private int count(Collection<BeAFixResult.BeAFixTest> tests, boolean positive) {
        int count = 0;
        for (BeAFixResult.BeAFixTest test : tests) {
            count += countTest(test, positive);
        }
        return count;
    }

    private int countTest(BeAFixResult.BeAFixTest test, boolean positive) {
        int count = 0;
        if (test.isPositiveAndNegativeBranch()) {
            if (positive) {
                count += countTest(test.getPositiveAndNegativeBranches().fst(), true);
            } else {
                count += countTest(test.getPositiveAndNegativeBranches().snd(), false);
            }
        } else if (positive && test.isMultipleBranch() && test.isPositiveBranch()) {
            count += test.getAlternateBranches().stream().map(t -> countTest(t, true)).reduce(0, Integer::sum);
        } else if (!positive && test.isMultipleBranch() && test.isNegativeBranch()) {
            count += test.getAlternateBranches().stream().map(t -> countTest(t, false)).reduce(0, Integer::sum);
        } else if (positive && test.isPositiveBranch()) {
            count++;
            if (test.isRelated())
                count++;
        } else if (!positive && test.isNegativeBranch()) {
            count++;
            if (test.isRelated())
                count++;
        }
        return count;
    }

    private Path getFullTestFilePathFromCandidate(FixCandidate candidate) {
        return getFullTestFilePathFromId(candidate.id());
    }

    private Path getFullTestFilePathFromNodeId(String nodeId) {
        return getFullTestFilePathFromId(nodeId.substring(2));
    }

    private Path getFullTestFilePathFromId(String id) {
        String testFileName = id + ".tests";
        return Paths.get(graphsFolder.toString(), TESTS_FOLDER, testFileName);
    }

    private Path fileNameToFullPath(String fileName) {
        return Paths.get(graphsFolder.toString(), fileName);
    }

    private static final class Node {
        private final String id;
        private final NODE_TYPE nodeType;
        private final Map<String, Node> descendants;
        private String extraInformation;

        public static Node leaf(NODE_TYPE nodeType, String id) {
            return new Node(nodeType, id);
        }

        public void addDescendant(NODE_TYPE nodeType, String id, String extraInformation) {
            assert repOK() : "This RepairGraph instance does not satisfy the repOK (DAG)";
            if (nodeType == null)
                throw new IllegalArgumentException("nodeType can't be null");
            if (id == null || id.isEmpty())
                throw new IllegalArgumentException("id can't be null or empty");
            if (checkDescendantRules(nodeType)) {
                if (this.id.compareTo(id) == 0)
                    throw new IllegalArgumentException("The descendant id is the same as this node");
                if (descendants.containsKey(id))
                    throw new IllegalArgumentException("This node already has a descendant with id " + id);
                Node descendant = leaf(nodeType, id);
                descendant.extraInformation(extraInformation);
                descendants.put(id, descendant);
            } else {
                throw new IllegalArgumentException("A node of type [" + nodeType + "] can't be descendant of a node of type [" + this.nodeType + "]");
            }
            assert repOK() : "This RepairGraph instance does not satisfy the repOK (DAG)";
        }

        public Collection<Node> getDescendants() {
            assert repOK() : "This RepairGraph instance does not satisfy the repOK (DAG)";
            return new LinkedList<>(descendants.values());
        }

        public boolean isLeaf() {
            assert repOK() : "This RepairGraph instance does not satisfy the repOK (DAG)";
            return this.descendants.isEmpty();
        }

        public Optional<Node> searchNode(String id) {
            assert repOK() : "This RepairGraph instance does not satisfy the repOK (DAG)";
            if (id == null || id.isEmpty())
                throw new IllegalArgumentException("id can't be null or empty");
            if (id.compareTo(this.id) == 0)
                return Optional.of(this);
            if (isLeaf())
                return Optional.empty();
            Node target = this.descendants.get(id);
            if (target != null) {
                assert repOK() : "This RepairGraph instance does not satisfy the repOK (DAG)";
                return Optional.of(target);
            }
            for (Node descendant : this.descendants.values()) {
                if (!descendant.isLeaf()) {
                    Optional<Node> targetOp = descendant.searchNode(id);
                    if (targetOp.isPresent()) {
                        assert repOK() : "This RepairGraph instance does not satisfy the repOK (DAG)";
                        return targetOp;
                    }
                }
            }
            assert repOK() : "This RepairGraph instance does not satisfy the repOK (DAG)";
            return Optional.empty();
        }

        public void extraInformation(String extraInformation) {
            this.extraInformation = extraInformation;
        }

        public String extraInformation() {
            return extraInformation;
        }

        private Node(NODE_TYPE nodeType, String id) {
            if (nodeType == null)
                throw new IllegalArgumentException("nodeType can't be null");
            if (id == null || id.isEmpty())
                throw new IllegalArgumentException("id can't be null or empty");
            this.nodeType = nodeType;
            this.id = id;
            descendants = new HashMap<>();
            assert repOK() : "This RepairGraph instance does not satisfy the repOK (DAG)";
        }

        private boolean checkDescendantRules(NODE_TYPE nodeType) {
            switch (this.nodeType) {
                case ORIGINAL: return nodeType.equals(NODE_TYPE.AREPAIR_CALL);
                case FIX_FAUX_SPURIOUS:
                case FIX_SPURIOUS: return nodeType.equals(NODE_TYPE.MAX_LAP) || nodeType.equals(NODE_TYPE.TEST_GENERATION) || nodeType.equals(NODE_TYPE.TIMEOUT);
                case TEST_GENERATION: return nodeType.equals(NODE_TYPE.AREPAIR_CALL) || nodeType.equals(NODE_TYPE.NO_TESTS);
                case AREPAIR_CALL: {
                    switch (nodeType) {
                        case ORIGINAL:
                        case AREPAIR_CALL:
                        case TIMEOUT:
                        case TEST_GENERATION:
                        case MAX_LAP:
                        case NO_TESTS:
                            return false;
                        case FIX_SPURIOUS:
                        case NO_FIX:
                        case FIX_REAL:
                        case FIX_FAUX_SPURIOUS:
                            return true;
                    }
                    break;
                }
                case NO_TESTS:
                case NO_FIX:
                case FIX_REAL:
                case MAX_LAP:
                case TIMEOUT:
                    return false;
            }
            return false;
        }

        public boolean repOK() {
            if (searchNode(id).isPresent())
                return false;
            for (Node descendant : descendants.values()) {
                if (!descendant.repOK())
                    return false;
            }
            return true;
        }

        @Override
        public String toString() {
            assert repOK() : "This RepairGraph instance does not satisfy the repOK (DAG)";
            return nodeType + "_" + id;
        }

    }

}
