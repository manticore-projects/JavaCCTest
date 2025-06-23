import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * JavaCC Grammar Token Scanner
 * Identifies tokens that appear only once in productions and suggests 
 * replacements with LOOKAHEAD predicates to optimize parser performance.
 */
public class JavaCCTokenScanner {

    private static final Pattern TOKEN_DEFINITION_PATTERN =
            Pattern.compile("<\\s*([A-Z_][A-Z0-9_]*)\\s*>\\s*:", Pattern.MULTILINE);

    private static final Pattern TOKEN_USAGE_PATTERN =
            Pattern.compile("<\\s*([A-Z][A-Z0-9_]*)\\s*>", Pattern.MULTILINE);

    private static final Pattern TOKEN_BLOCK_PATTERN =
            Pattern.compile("TOKEN\\s*:\\s*(?:/\\*.*?\\*/\\s*)?\\{.*?\\}", Pattern.MULTILINE | Pattern.DOTALL);

    private static final Pattern STRING_LITERAL_PATTERN =
            Pattern.compile("\"([^\"\\\\]|\\\\.)*\"", Pattern.MULTILINE);

    private static final Pattern COMMENT_PATTERN =
            Pattern.compile("//.*?$|/\\*.*?\\*/", Pattern.MULTILINE | Pattern.DOTALL);

    public static void main(String[] args) {
        if (args.length != 1) {
//            System.err.println("Usage: java JavaCCTokenScanner <grammar-file.jj>");
//            System.exit(1);
            args = new String[]{"/home/are/Documents/src/JavaCCTest/src/main/jjtree/com/manticore/parser/explicit/TestParser.jjt"};
        }

        String grammarFile = args[0];
        try {
            JavaCCTokenScanner scanner = new JavaCCTokenScanner();
            scanner.analyzeGrammar(grammarFile);
        } catch (IOException e) {
            System.err.println("Error reading file: " + e.getMessage());
            System.exit(1);
        }
    }

    public void analyzeGrammar(String grammarFile) throws IOException {
        String content = Files.readString(Paths.get(grammarFile));

        System.out.println("=== DEBUG: Reading file: " + grammarFile + " ===");
        System.out.println("File size: " + content.length() + " characters");
        System.out.println("First 300 characters of original file:");
        System.out.println(content.substring(0, Math.min(300, content.length())));
        System.out.println("...\n");

        // Find all token definitions in original content (before cleaning)
        Set<String> definedTokens = findDefinedTokens(content);

        // Remove comments and string literals only for usage counting
        String cleanContent = removeCommentsAndStrings(content);

        // Count token usages in productions (excluding definitions)
        Map<String, List<TokenUsage>> tokenUsages = countTokenUsages(content, definedTokens);

        // Find single-use tokens
        List<String> singleUseTokens = findSingleUseTokens(tokenUsages);

        // Generate report and suggestions
        generateReport(singleUseTokens, tokenUsages, content);

        // Debug output - show all tokens and their usage counts
        generateDebugReport(tokenUsages);

        // Perform automatic replacements if single-use tokens found
        if (!singleUseTokens.isEmpty()) {
            performReplacements(grammarFile, content, singleUseTokens, tokenUsages);
        }
    }

    private String removeCommentsAndStrings(String content) {
        // First remove comments
        String withoutComments = COMMENT_PATTERN.matcher(content).replaceAll("");

        // Then remove string literals (but keep track of positions for later)
        return STRING_LITERAL_PATTERN.matcher(withoutComments).replaceAll("\"REMOVED_STRING\"");
    }

    private Set<String> findDefinedTokens(String content) {
        Set<String> tokens = new HashSet<>();

        // Find all TOKEN blocks first - work with original content
        Matcher tokenBlockMatcher = TOKEN_BLOCK_PATTERN.matcher(content);

        System.out.println("=== DEBUG: Searching for TOKEN blocks in original content ===");
        int blockCount = 0;
        while (tokenBlockMatcher.find()) {
            blockCount++;
            String tokenBlock = tokenBlockMatcher.group();
            System.out.println("Found TOKEN block #" + blockCount + " (length: " + tokenBlock.length() + ")");

            // Show first part of the block for debugging
            System.out.println("First 300 chars of block #" + blockCount + ":");
            System.out.println(tokenBlock.substring(0, Math.min(300, tokenBlock.length())));
            System.out.println("...");

            // Within each TOKEN block, find all token definitions
            // JavaCC format is: <TOKEN_NAME: definition> not <TOKEN_NAME>: definition
            Pattern tokenDefPattern = Pattern.compile("<\\s*([A-Z][A-Z0-9_]*)\\s*:", Pattern.MULTILINE);
            Matcher matcher = tokenDefPattern.matcher(tokenBlock);

            System.out.println("  Searching for tokens with pattern: <TOKEN_NAME:");
            System.out.println("  Looking for matches in block content...");

            int tokenCount = 0;
            while (matcher.find()) {
                String tokenName = matcher.group(1);
                tokens.add(tokenName);
                tokenCount++;
                System.out.println("  Token " + tokenCount + ": " + tokenName + " (found at position " + matcher.start() + ")");
            }

            // If no tokens found, let's try a different approach - extract from the bracket contents we found
            if (tokenCount == 0) {
                System.out.println("  No tokens found with main pattern. Extracting from bracket contents...");
                Pattern simplePattern = Pattern.compile("<\\s*([A-Z][A-Z0-9_]*)\\s*:", Pattern.MULTILINE);
                Matcher simpleMatcher = simplePattern.matcher(tokenBlock);
                int count = 0;
                while (simpleMatcher.find()) {
                    String tokenName = simpleMatcher.group(1);
                    // Skip private tokens that start with #
                    if (!tokenName.startsWith("#")) {
                        tokens.add(tokenName);
                        count++;
                        System.out.println("    Token " + count + ": " + tokenName);
                    }
                }
                tokenCount = count;
            }
            System.out.println("  Tokens found in this block: " + tokenCount);
        }

        System.out.println("Total TOKEN blocks found: " + blockCount);
        System.out.println("Total defined tokens found: " + tokens.size());
        return tokens;
    }

    private Map<String, List<TokenUsage>> countTokenUsages(String content, Set<String> definedTokens) {
        Map<String, List<TokenUsage>> usages = new HashMap<>();

        // Initialize map for all defined tokens
        for (String token : definedTokens) {
            usages.put(token, new ArrayList<>());
        }

        // Remove all TOKEN blocks to avoid counting definitions as usages
        String contentWithoutTokenBlocks = TOKEN_BLOCK_PATTERN.matcher(content).replaceAll("");
        System.out.println("=== DEBUG: Content after removing TOKEN blocks ===");
        System.out.println("Remaining content length: " + contentWithoutTokenBlocks.length());
        System.out.println("First 500 chars of remaining content:");
        System.out.println(contentWithoutTokenBlocks.substring(0, Math.min(500, contentWithoutTokenBlocks.length())));
        System.out.println("...");

        // Find all token references in the remaining content (productions)
        String[] lines = contentWithoutTokenBlocks.split("\n");
        for (int lineNum = 0; lineNum < lines.length; lineNum++) {
            String line = lines[lineNum];

            Matcher matcher = TOKEN_USAGE_PATTERN.matcher(line);
            while (matcher.find()) {
                String tokenName = matcher.group(1);
                if (definedTokens.contains(tokenName)) {
                    usages.get(tokenName).add(new TokenUsage(lineNum + 1, matcher.start(), line.trim()));
                }
            }
        }

        return usages;
    }

    private List<String> findSingleUseTokens(Map<String, List<TokenUsage>> tokenUsages) {
        List<String> singleUseTokens = new ArrayList<>();

        for (Map.Entry<String, List<TokenUsage>> entry : tokenUsages.entrySet()) {
            if (entry.getValue().size() == 1) {
                singleUseTokens.add(entry.getKey());
            }
        }

        return singleUseTokens;
    }

    private void performReplacements(String originalFile, String content,
                                     List<String> singleUseTokens,
                                     Map<String, List<TokenUsage>> allUsages) {
        System.out.println("\n=== PERFORMING AUTOMATIC REPLACEMENTS ===");

        String modifiedContent = content;
        int replacementCount = 0;

        for (String tokenName : singleUseTokens) {
            // Extract the literal value for this token
            String tokenLiteral = extractTokenLiteral(tokenName, content);

            if (tokenLiteral != null) {
                System.out.println("Replacing <" + tokenName + "> with LOOKAHEAD for literal: \"" + tokenLiteral + "\"");

                // Create the replacement based on context
                String lookaheadReplacement = "LOOKAHEAD({nextIs(\"" + tokenLiteral + "\")})";

                // Replace all occurrences of <TOKEN_NAME> with the LOOKAHEAD predicate
                // But handle assignments specially
                // But NOT in TOKEN blocks (definitions)
                String beforeReplacement = modifiedContent;
                modifiedContent = replaceTokenUsage(modifiedContent, tokenName, lookaheadReplacement);

                // Debug: check if replacement actually happened
                if (beforeReplacement.equals(modifiedContent)) {
                    System.out.println("  WARNING: No replacements made for " + tokenName);
                } else {
                    System.out.println("  Successfully replaced " + tokenName + " references");
                }

                replacementCount++;
            } else {
                System.out.println("Could not extract literal for token: " + tokenName + " - skipping replacement");
            }
        }

        // Remove the token definitions from TOKEN blocks
        modifiedContent = removeTokenDefinitions(modifiedContent, singleUseTokens);

        // Add the helper methods to the parser
        modifiedContent = addHelperMethods(modifiedContent);

        // Write the modified grammar to a new file
        try {
            String outputFile = originalFile.replace(".jjt", "_optimized.jjt").replace(".jj", "_optimized.jj");
            Files.writeString(Paths.get(outputFile), modifiedContent);
            System.out.println("\nOptimized grammar written to: " + outputFile);
            System.out.println("Total replacements made: " + replacementCount);
            System.out.println("Removed " + singleUseTokens.size() + " token definitions");
        } catch (IOException e) {
            System.err.println("Error writing optimized file: " + e.getMessage());
        }
    }

    private String replaceTokenUsage(String content, String tokenName, String replacement) {
        // Handle both regular references and assignments differently
        String result = content;
        String tokenPattern = "<\\s*" + Pattern.quote(tokenName) + "\\s*>";

        // Split content into lines and process each line
        String[] lines = content.split("\n");
        StringBuilder sb = new StringBuilder();
        boolean inTokenBlock = false;

        for (String line : lines) {
            // Check if we're entering or leaving a TOKEN block
            if (line.trim().matches("TOKEN\\s*:.*\\{.*")) {
                inTokenBlock = true;
            } else if (line.trim().equals("}") && inTokenBlock) {
                inTokenBlock = false;
            }

            // Only replace if we're NOT in a TOKEN block
            if (!inTokenBlock) {
                // Check if this is an assignment (pattern: variable = <TOKEN>)
                if (line.matches(".*\\w+\\s*=\\s*" + tokenPattern + ".*")) {
                    // For assignments, replace with getToken("literal") call
                    String literal = extractTokenLiteral(tokenName, content);
                    String assignmentReplacement = "getToken(\"" + literal + "\")";
                    line = line.replaceAll(tokenPattern, assignmentReplacement);
                } else {
                    // For regular references, just use LOOKAHEAD
                    line = line.replaceAll(tokenPattern, replacement);
                }
            }

            sb.append(line).append("\n");
        }

        return sb.toString();
    }

    private String removeTokenDefinitions(String content, List<String> tokensToRemove) {
        String result = content;

        // Process each TOKEN block
        Pattern tokenBlockPattern = TOKEN_BLOCK_PATTERN;
        Matcher blockMatcher = tokenBlockPattern.matcher(result);

        StringBuffer sb = new StringBuffer();
        while (blockMatcher.find()) {
            String tokenBlock = blockMatcher.group();
            String cleanedBlock = removeTokensFromBlock(tokenBlock, tokensToRemove);
            blockMatcher.appendReplacement(sb, Matcher.quoteReplacement(cleanedBlock));
        }
        blockMatcher.appendTail(sb);

        return sb.toString();
    }

    private String removeTokensFromBlock(String tokenBlock, List<String> tokensToRemove) {
        String result = tokenBlock;

        for (String tokenName : tokensToRemove) {
            // Pattern to match token definition including the | separator if present
            // Handles: | <TOKEN_NAME: "value"> or <TOKEN_NAME: "value"> at start
            String pattern1 = "\\|\\s*<\\s*" + Pattern.quote(tokenName) + "\\s*:[^>]*>";
            String pattern2 = "<\\s*" + Pattern.quote(tokenName) + "\\s*:[^>]*>\\s*\\|?";

            result = result.replaceAll(pattern1, "");
            result = result.replaceAll(pattern2, "");
        }

        // Clean up any double | or trailing |
        result = result.replaceAll("\\|\\s*\\|", "|");
        result = result.replaceAll("\\|\\s*}", "}");

        return result;
    }

    private String addHelperMethods(String content) {
        // Find a good place to add our helper methods and production
        String[] lines = content.split("\n");
        StringBuilder result = new StringBuilder();
        boolean addedMethods = false;
        boolean addedProduction = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            // Add helper method to parser class
            if (line.trim().equals("PARSER_END(TestParser)") && !addedMethods) {
                // Insert helper method before PARSER_END
                result.append("\n");
                result.append("    /**\n");
                result.append("     * Helper method to check if next token matches expected text\n");
                result.append("     * Generated by JavaCCTokenScanner optimization\n");
                result.append("     */\n");
                result.append("    private boolean nextIs(String expected) {\n");
                result.append("        Token t = getToken(1);\n");
                result.append("        return expected.equalsIgnoreCase(t.image);\n");
                result.append("    }\n");
                result.append("}\n");
                result.append("\n");
                result.append("PARSER_END(TestParser)\n");
                addedMethods = true;
                continue;
            }

            // Add production at the end of the file
            if (line.trim().equals("}") && i == lines.length - 1 && !addedProduction) {
                // Remove the last closing brace temporarily
                result.append("\n");
                result.append("/*\n");
                result.append(" * Helper production to match and return a token with specific text\n");
                result.append(" * Generated by JavaCCTokenScanner optimization\n");
                result.append(" */\n");
                result.append("Token getToken(String expected) :\n");
                result.append("{\n");
                result.append("    Token t;\n");
                result.append("}\n");
                result.append("{\n");
                result.append("    t=<S_IDENTIFIER>\n");
                result.append("    {\n");
                result.append("        if (!expected.equalsIgnoreCase(t.image)) {\n");
                result.append("            throw new ParseException(\"Expected '\" + expected + \"' but found '\" + t.image + \"' at line \" + t.beginLine);\n");
                result.append("        }\n");
                result.append("        return t;\n");
                result.append("    }\n");
                result.append("}\n");
                result.append("\n");
                result.append("}\n"); // Restore the final closing brace
                addedProduction = true;
                continue;
            }

            result.append(line).append("\n");
        }

        return result.toString();
    }

    private void generateDebugReport(Map<String, List<TokenUsage>> allUsages) {
        System.out.println("\n=== DEBUG: All Token Usage Counts ===");

        // Sort tokens by usage count, then alphabetically
        allUsages.entrySet().stream()
                 .sorted((e1, e2) -> {
                     int countCompare = Integer.compare(e1.getValue().size(), e2.getValue().size());
                     if (countCompare != 0) return countCompare;
                     return e1.getKey().compareTo(e2.getKey());
                 })
                 .forEach(entry -> {
                     String token = entry.getKey();
                     List<TokenUsage> usages = entry.getValue();
                     System.out.printf("%-25s: %d usage(s)", token, usages.size());

                     if (usages.size() > 0 && usages.size() <= 3) {
                         System.out.print(" at lines: ");
                         String lines = usages.stream()
                                              .map(u -> String.valueOf(u.lineNumber))
                                              .collect(java.util.stream.Collectors.joining(", "));
                         System.out.print(lines);
                     }
                     System.out.println();
                 });
    }

    private void generateReport(List<String> singleUseTokens,
                                Map<String, List<TokenUsage>> allUsages,
                                String originalContent) {
        System.out.println("=== JavaCC Grammar Token Analysis Report ===\n");

        if (singleUseTokens.isEmpty()) {
            System.out.println("No single-use tokens found. All tokens are used multiple times.");
            return;
        }

        System.out.println("Found " + singleUseTokens.size() + " token(s) used only once:\n");

        // Sort tokens alphabetically for consistent output
        Collections.sort(singleUseTokens);

        for (String token : singleUseTokens) {
            TokenUsage usage = allUsages.get(token).get(0);
            System.out.println("Token: " + token);
            System.out.println("  Used at line " + usage.lineNumber + ", column " + (usage.columnStart + 1));
            System.out.println("  Context: " + usage.context);

            // Try to extract the token's literal value from the original content
            String tokenLiteral = extractTokenLiteral(token, originalContent);
            if (tokenLiteral != null) {
                System.out.println("  Suggestion: Replace <" + token + "> with:");
                System.out.println("    LOOKAHEAD({\"" + tokenLiteral + "\".equalsIgnoreCase(getToken(1).image);})");
            } else {
                System.out.println("  Suggestion: Replace <" + token + "> with:");
                System.out.println("    LOOKAHEAD({\"TOKEN_VALUE\".equalsIgnoreCase(getToken(1).image);})");
                System.out.println("    (Replace TOKEN_VALUE with the actual token string)");
            }
            System.out.println();
        }

        // Summary statistics
        System.out.println("=== Summary ===");
        System.out.println("Total defined tokens: " + allUsages.size());
        System.out.println("Single-use tokens: " + singleUseTokens.size());
        System.out.println("Multi-use tokens: " + (allUsages.size() - singleUseTokens.size()));

        long totalUsages = allUsages.values().stream().mapToLong(List::size).sum();
        System.out.println("Total token usages: " + totalUsages);

        if (!singleUseTokens.isEmpty()) {
            System.out.println("\nOptimization potential: " + singleUseTokens.size() +
                               " tokens can be converted to LOOKAHEAD predicates.");
        }
    }

    private String extractTokenLiteral(String tokenName, String content) {
        // Look for the token definition and try to extract its literal value
        // JavaCC format: <TOKEN_NAME: "literal"> or <TOKEN_NAME:"literal">
        // Handle both single-line and multi-line token definitions

        // First try to find the token definition line
        String[] lines = content.split("\n");
        for (String line : lines) {
            // Look for lines containing this token definition
            if (line.contains("<" + tokenName + ":")) {
                System.out.println("  Found token definition line: " + line.trim());

                // Extract everything after the colon within the angle brackets
                Pattern tokenDefPattern = Pattern.compile("<\\s*" + Pattern.quote(tokenName) + "\\s*:\\s*\"([^\"]+)\"");
                Matcher matcher = tokenDefPattern.matcher(line);

                if (matcher.find()) {
                    String literal = matcher.group(1);
                    System.out.println("  Extracted literal: \"" + literal + "\"");
                    return literal;
                } else {
                    // Try without quotes for special characters like @
                    Pattern specialPattern = Pattern.compile("<\\s*" + Pattern.quote(tokenName) + "\\s*:\\s*\"([^\"]+)\"");
                    Matcher specialMatcher = specialPattern.matcher(line);
                    if (specialMatcher.find()) {
                        String literal = specialMatcher.group(1);
                        System.out.println("  Extracted special literal: \"" + literal + "\"");
                        return literal;
                    }
                    System.out.println("  Could not extract literal from: " + line.trim());
                }
            }
        }

        System.out.println("  Token definition not found for: " + tokenName);
        return null;
    }

    /**
     * Represents a usage of a token in the grammar
     */
    private static class TokenUsage {
        final int lineNumber;
        final int columnStart;
        final String context;

        TokenUsage(int lineNumber, int columnStart, String context) {
            this.lineNumber = lineNumber;
            this.columnStart = columnStart;
            this.context = context;
        }
    }
}