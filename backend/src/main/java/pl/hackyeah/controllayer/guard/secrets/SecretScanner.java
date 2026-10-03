package pl.hackyeah.controllayer.guard.secrets;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * Szybki skan sekretów na regułach w semantyce Gitleaks. Ten sam wynik co "regex każdej reguły na
 * całym tekście", ale z kosztem zależnym od liczby trafień keywordów, a nie od liczby reguł:
 *
 * <ol>
 *   <li>Jeden przebieg {@link KeywordIndex} (Aho-Corasick) po tekście. Brak keywordu = koniec;
 *       to typowy przypadek i kosztuje ułamek mikrosekundy na krótki prompt.</li>
 *   <li>Dla reguły kontekstowej ("nazwa operator wartość") tani precheck: czy tuż za keywordem stoi
 *       operator przypisania. Proza w stylu "rotate an API key safely" kończy się tutaj.</li>
 *   <li>Regex tylko w oknie {@value #WINDOW} znaków wokół keywordu (okna jednej reguły są scalane),
 *       z przezroczystymi granicami regionu, więc {@code \b} i lookaroundy działają jak na całym tekście.
 *       Reguły bez górnej granicy długości dopasowania skanują cały tekst.</li>
 *   <li>Filtry Gitleaks: entropia, cyfra w sekrecie reguł {@code generic*}, allowlisty globalne i reguły.</li>
 * </ol>
 *
 * Reguły kontekstowe są kompilowane bez wiodącego {@code [\w.-]{0,50}?}; dla allowlist typu
 * {@code match} początek dopasowania jest odtwarzany tak, jak dopasowałby go oryginalny regex.
 * Niemutowalny i bezpieczny wątkowo.
 */
public final class SecretScanner {

    /** Promień okna wokół keywordu; większy niż najdłuższe ograniczone dopasowanie w paczce. */
    static final int WINDOW = 512;
    /** Zasięg szukania operatora za keywordem prefiltra: 20 + 3 z szablonu + 29 zapasu na dłuższy keyword regexu. */
    static final int OPERATOR_LOOKAHEAD = 52;
    /**
     * Po usunięciu {@code [\w.-]{0,50}?} dopasowanie reguły kontekstowej zaczyna się na keywordzie regexu,
     * który obejmuje keyword prefiltra. Starty sprawdzamy więc tylko w {@code [koniec keywordu - 64, koniec)}.
     */
    static final int CONTEXT_LOOKBEHIND = 64;
    private static final int MAX_NAME_PREFIX = 50;

    private final List<SecretRule> rules;
    private final Allowlist globalAllowlist;
    private final KeywordIndex index;
    private final int[] alwaysCandidates;

    public SecretScanner(List<SecretRule> rules, Allowlist globalAllowlist) {
        this.rules = List.copyOf(rules);
        this.globalAllowlist = globalAllowlist;
        this.index = new KeywordIndex(this.rules.stream().map(SecretRule::keywords).toList());
        this.alwaysCandidates = java.util.stream.IntStream.range(0, this.rules.size())
                .filter(i -> this.rules.get(i).keywords().isEmpty())
                .toArray();
    }

    public List<SecretRule> rules() {
        return rules;
    }

    int automatonStates() {
        return index.states();
    }

    /** Skanuje tekst; reguły z {@code disabledRules} są pomijane. Wynik posortowany po pozycji. */
    public List<SecretFinding> scan(String text, Set<String> disabledRules) {
        int length = text.length();
        int[][] windows = null;   // per reguła: pary [from, to), scalane w locie
        int[] windowCount = null;
        boolean[] fullText = null;

        int state = 0;
        for (int i = 0; i < length; i++) {
            state = index.step(state, text.charAt(i));
            int[] hits = index.outputs(state);
            if (hits.length == 0) {
                continue;
            }
            if (windows == null) {
                windows = new int[rules.size()][];
                windowCount = new int[rules.size()];
                fullText = new boolean[rules.size()];
            }
            int keywordEnd = i + 1;
            for (int ruleIndex : hits) {
                SecretRule rule = rules.get(ruleIndex);
                if (fullText[ruleIndex] || disabledRules.contains(rule.id())) {
                    continue;
                }
                if (rule.contextual() && !operatorFollows(text, keywordEnd)) {
                    continue;
                }
                // Reguła kontekstowa zna zakres startów (keyword), więc nawet bez górnej granicy długości
                // nie potrzebuje pełnego skanu: lookingAt z regionem do końca tekstu w matchStarts.
                if (!rule.bounded() && !rule.contextual()) {
                    fullText[ruleIndex] = true;
                    continue;
                }
                if (rule.contextual()) {
                    // zakres możliwych startów dopasowania, nie okno skanu
                    addWindow(windows, windowCount, ruleIndex, Math.max(0, keywordEnd - CONTEXT_LOOKBEHIND), keywordEnd);
                } else {
                    addWindow(windows, windowCount, ruleIndex, Math.max(0, keywordEnd - WINDOW),
                            Math.min(length, keywordEnd + WINDOW));
                }
            }
        }

        var findings = new ArrayList<SecretFinding>();
        for (int ruleIndex : alwaysCandidates) {
            if (!disabledRules.contains(rules.get(ruleIndex).id())) {
                matchRegion(rules.get(ruleIndex), text, 0, length, findings);
            }
        }
        if (windows == null) {
            return findings;
        }
        for (int ruleIndex = 0; ruleIndex < rules.size(); ruleIndex++) {
            SecretRule rule = rules.get(ruleIndex);
            if (fullText[ruleIndex]) {
                matchRegion(rule, text, 0, length, findings);
            } else if (windows[ruleIndex] != null) {
                int[] ranges = windows[ruleIndex];
                int resumeAt = 0;
                for (int w = 0; w < windowCount[ruleIndex]; w++) {
                    if (rule.contextual()) {
                        resumeAt = matchStarts(rule, text, Math.max(resumeAt, ranges[2 * w]), ranges[2 * w + 1], findings);
                    } else {
                        matchRegion(rule, text, ranges[2 * w], ranges[2 * w + 1], findings);
                    }
                }
            }
        }
        findings.sort(Comparator.comparingInt(SecretFinding::start).thenComparingInt(SecretFinding::end));
        return findings;
    }

    /**
     * Semantyka {@code find()} ograniczona do startów z {@code [fromStart, toStart)}: dla kolejnych pozycji
     * {@code lookingAt}, po trafieniu wznawiamy od końca dopasowania. Zwraca pozycję wznowienia, żeby
     * kolejny zakres tej reguły nie zgłosił nakładającego się dopasowania.
     */
    private int matchStarts(SecretRule rule, String text, int fromStart, int toStart, List<SecretFinding> findings) {
        Matcher matcher = rule.pattern().matcher(text);
        matcher.useTransparentBounds(true).useAnchoringBounds(false);
        int s = fromStart;
        while (s < toStart) {
            matcher.region(s, rule.bounded() ? Math.min(text.length(), s + WINDOW) : text.length());
            if (!matcher.lookingAt()) {
                s++;
                continue;
            }
            accept(rule, true, text, matcher, findings);
            s = Math.max(s + 1, matcher.end());
        }
        return s;
    }

    /**
     * Referencyjny, wolny skan: prefiltr {@code contains} i regex z paczki 1:1 na całym tekście, jak
     * w Gitleaks. Służy testom równoważności szybkiej ścieżki; nie używać w ruchu.
     */
    List<SecretFinding> scanReference(String text) {
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        var findings = new ArrayList<SecretFinding>();
        for (SecretRule rule : rules) {
            if (rule.keywords().isEmpty() || rule.keywords().stream().anyMatch(lower::contains)) {
                matchRegion(rule, rule.originalPattern(), false, text, 0, text.length(), findings);
            }
        }
        findings.sort(Comparator.comparingInt(SecretFinding::start).thenComparingInt(SecretFinding::end));
        return findings;
    }

    private void matchRegion(SecretRule rule, String text, int from, int to, List<SecretFinding> findings) {
        matchRegion(rule, rule.pattern(), rule.contextual(), text, from, to, findings);
    }

    private void matchRegion(SecretRule rule, java.util.regex.Pattern pattern, boolean prefixStripped, String text,
            int from, int to, List<SecretFinding> findings) {
        Matcher matcher = pattern.matcher(text);
        matcher.useTransparentBounds(true).useAnchoringBounds(false).region(from, to);
        while (matcher.find()) {
            accept(rule, prefixStripped, text, matcher, findings);
        }
    }

    /** Filtry Gitleaks dla jednego dopasowania: grupa sekretu, entropia, cyfra w generic, allowlisty. */
    private void accept(SecretRule rule, boolean prefixStripped, String text, Matcher matcher, List<SecretFinding> findings) {
        int group = secretGroup(rule, matcher);
        if (group < 0) {
            return;
        }
        int start = matcher.start(group);
        int end = matcher.end(group);
        String secret = text.substring(start, end);
        if (rule.minEntropy() > 0) {
            if (shannonEntropy(secret) <= rule.minEntropy()) {
                return;
            }
            if (rule.id().startsWith("generic") && !containsDigit(secret)) {
                return;
            }
        }
        if (isAllowed(rule, prefixStripped, text, matcher, secret)) {
            return;
        }
        findings.add(new SecretFinding(rule.id(), start, end));
    }

    private boolean isAllowed(SecretRule rule, boolean prefixStripped, String text, Matcher matcher, String secret) {
        int matchStart = prefixStripped ? originalMatchStart(text, matcher.start()) : matcher.start();
        int matchEnd = matcher.end();
        String match = text.substring(matchStart, matchEnd);
        String line = line(text, matchStart, matchEnd);
        if (globalAllowlist.allows(secret, match, line)) {
            return true;
        }
        for (Allowlist allowlist : rule.allowlists()) {
            if (allowlist.allows(secret, match, line)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Gdzie zacząłby się match oryginalnego regexu z leniwym {@code [\w.-]{0,50}?}: najwcześniejsza
     * pozycja, z której ciąg znaków {@code [\w.-]} dochodzi do keywordu (maks. 50 znaków).
     */
    static int originalMatchStart(String text, int keywordStart) {
        int start = keywordStart;
        while (start > 0 && keywordStart - start < MAX_NAME_PREFIX && isNameChar(text.charAt(start - 1))) {
            start--;
        }
        return start;
    }

    private static int secretGroup(SecretRule rule, Matcher matcher) {
        if (rule.wholeMatchSecret()) {
            return 0;
        }
        if (rule.secretGroup() > 0) {
            int group = rule.secretGroup();
            return group <= matcher.groupCount() && matcher.start(group) >= 0 ? group : -1;
        }
        for (int group = 1; group <= matcher.groupCount(); group++) {
            if (matcher.start(group) >= 0 && matcher.end(group) > matcher.start(group)) {
                return group;
            }
        }
        return 0;
    }

    /**
     * Precheck szablonu Gitleaks {@code keyword [ \t\w.-]{0,20} [\s'"]{0,3} operator}. Od końca keywordu
     * prefiltra idziemy po znakach nazwy, odstępach i cudzysłowach; pierwszy inny znak musi być
     * operatorem, i to w zasięgu {@value #OPERATOR_LOOKAHEAD} znaków (20 + 3 z szablonu + zapas na
     * keyword regexu dłuższy niż keyword prefiltra, np. {@code passw} → {@code password}). To
     * nadaproksymacja: nie odrzuca żadnego dopasowania szablonu, a proza bez operatora odpada.
     */
    static boolean operatorFollows(String text, int from) {
        int length = text.length();
        int end = Math.min(length, from + OPERATOR_LOOKAHEAD);
        for (int i = from; i < end; i++) {
            char ch = text.charAt(i);
            if (isNameChar(ch) || isSpaceOrQuote(ch)) {
                continue;
            }
            char nextCh = i + 1 < length ? text.charAt(i + 1) : 0;
            // operatory szablonu: = > : (także :=, ::=) => , oraz dwuznakowe || i ?=
            return switch (ch) {
                case '=', '>', ':', ',' -> true;
                case '|' -> nextCh == '|';
                case '?' -> nextCh == '=';
                default -> false;
            };
        }
        return false;
    }

    private static boolean isSpaceOrQuote(char ch) {
        return ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' || ch == '\f' || ch == 0x0B || ch == '\'' || ch == '"';
    }

    static double shannonEntropy(String value) {
        if (value.isEmpty()) {
            return 0;
        }
        var counts = new java.util.HashMap<Character, Integer>();
        for (int i = 0; i < value.length(); i++) {
            counts.merge(value.charAt(i), 1, Integer::sum);
        }
        double entropy = 0;
        for (int count : counts.values()) {
            double p = (double) count / value.length();
            entropy -= p * (Math.log(p) / Math.log(2));
        }
        return entropy;
    }

    private static boolean containsDigit(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isDigit(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNameChar(char ch) {
        // odpowiednik [\w.-] w java.util.regex bez UNICODE_CHARACTER_CLASS (ASCII)
        return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9')
                || ch == '_' || ch == '.' || ch == '-';
    }

    private static String line(String text, int start, int end) {
        int lineStart = text.lastIndexOf('\n', Math.max(0, start - 1)) + 1;
        if (start == 0) {
            lineStart = 0;
        }
        int lineEnd = text.indexOf('\n', end);
        return text.substring(lineStart, lineEnd < 0 ? text.length() : lineEnd);
    }

    private static void addWindow(int[][] windows, int[] counts, int rule, int from, int to) {
        int[] ranges = windows[rule];
        if (ranges == null) {
            windows[rule] = new int[] {from, to, 0, 0};
            counts[rule] = 1;
            return;
        }
        int last = 2 * (counts[rule] - 1);
        if (from <= ranges[last + 1]) {      // okna przychodzą rosnąco: nakładające się scalamy
            ranges[last + 1] = Math.max(ranges[last + 1], to);
            return;
        }
        if (last + 3 >= ranges.length) {
            ranges = windows[rule] = Arrays.copyOf(ranges, ranges.length * 2);
        }
        ranges[last + 2] = from;
        ranges[last + 3] = to;
        counts[rule]++;
    }
}
