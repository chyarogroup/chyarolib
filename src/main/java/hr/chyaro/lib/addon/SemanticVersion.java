package hr.chyaro.lib.addon;

import java.util.ArrayList;
import java.util.List;

public record SemanticVersion(int major, int minor, int patch, List<String> prerelease) implements Comparable<SemanticVersion> {
    public SemanticVersion {
        prerelease = List.copyOf(prerelease);
    }

    public static SemanticVersion parse(String input) {
        String clean = input == null ? "" : input.trim();
        if (clean.startsWith("v")) clean = clean.substring(1);
        String[] buildSplit = clean.split("\\+", 2);
        String[] preSplit = buildSplit[0].split("-", 2);
        String[] core = preSplit[0].split("\\.");
        if (core.length < 1 || core.length > 3) throw new IllegalArgumentException("Invalid semantic version: " + input);
        int major = number(core[0], input);
        int minor = core.length > 1 ? number(core[1], input) : 0;
        int patch = core.length > 2 ? number(core[2], input) : 0;
        List<String> pre = preSplit.length == 1 ? List.of() : List.of(preSplit[1].split("\\."));
        return new SemanticVersion(major, minor, patch, pre);
    }

    private static int number(String value, String source) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException("Invalid semantic version: " + source); }
    }

    @Override
    public int compareTo(SemanticVersion other) {
        int result = Integer.compare(major, other.major);
        if (result == 0) result = Integer.compare(minor, other.minor);
        if (result == 0) result = Integer.compare(patch, other.patch);
        if (result != 0) return result;
        if (prerelease.isEmpty() && other.prerelease.isEmpty()) return 0;
        if (prerelease.isEmpty()) return 1;
        if (other.prerelease.isEmpty()) return -1;
        int length = Math.max(prerelease.size(), other.prerelease.size());
        for (int i = 0; i < length; i++) {
            if (i >= prerelease.size()) return -1;
            if (i >= other.prerelease.size()) return 1;
            String left = prerelease.get(i);
            String right = other.prerelease.get(i);
            boolean leftNumber = left.matches("\\d+");
            boolean rightNumber = right.matches("\\d+");
            if (leftNumber && rightNumber) result = Integer.compare(Integer.parseInt(left), Integer.parseInt(right));
            else if (leftNumber != rightNumber) result = leftNumber ? -1 : 1;
            else result = compareTextIdentifier(left, right);
            if (result != 0) return result;
        }
        return 0;
    }

    private static int compareTextIdentifier(String left, String right) {
        int leftNumberStart = trailingNumberStart(left);
        int rightNumberStart = trailingNumberStart(right);
        String leftPrefix = left.substring(0, leftNumberStart);
        String rightPrefix = right.substring(0, rightNumberStart);
        int prefix = leftPrefix.compareToIgnoreCase(rightPrefix);
        if (prefix != 0) return prefix;
        if (leftNumberStart < left.length() && rightNumberStart < right.length()) {
            return Integer.compare(Integer.parseInt(left.substring(leftNumberStart)),
                    Integer.parseInt(right.substring(rightNumberStart)));
        }
        return left.compareToIgnoreCase(right);
    }

    private static int trailingNumberStart(String value) {
        int index = value.length();
        while (index > 0 && Character.isDigit(value.charAt(index - 1))) index--;
        return index;
    }

    public static boolean satisfies(String version, String range) {
        if (range == null || range.isBlank() || range.equals("*")) return true;
        SemanticVersion actual = parse(version);
        List<String> constraints = new ArrayList<>();
        for (String part : range.trim().split("\\s+")) if (!part.isBlank()) constraints.add(part);
        for (String constraint : constraints) {
            String operator;
            if (constraint.startsWith(">=") || constraint.startsWith("<=")) operator = constraint.substring(0, 2);
            else if (constraint.startsWith(">") || constraint.startsWith("<") || constraint.startsWith("=")) operator = constraint.substring(0, 1);
            else operator = "=";
            String expectedText = constraint.substring(operator.equals("=") && !constraint.startsWith("=") ? 0 : operator.length());
            int comparison = actual.compareTo(parse(expectedText));
            boolean matches = switch (operator) {
                case ">=" -> comparison >= 0;
                case "<=" -> comparison <= 0;
                case ">" -> comparison > 0;
                case "<" -> comparison < 0;
                default -> comparison == 0;
            };
            if (!matches) return false;
        }
        return true;
    }
}
