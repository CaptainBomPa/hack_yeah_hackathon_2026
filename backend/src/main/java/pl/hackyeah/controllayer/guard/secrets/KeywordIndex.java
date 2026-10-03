package pl.hackyeah.controllayer.guard.secrets;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

/**
 * Aho-Corasick jako pełne DFA nad ASCII, case-insensitive. Jeden przebieg po tekście znajduje
 * wszystkie wystąpienia keywordów wszystkich reguł naraz; koszt nie zależy od liczby reguł.
 *
 * <p>Tablica przejść jest płaska ({@code state * 128 + char}), więc krok automatu to jeden odczyt.
 * Znak spoza ASCII resetuje automat (keywordy paczki są ASCII). Klasa jest niemutowalna po
 * konstrukcji i bezpieczna wątkowo.
 */
final class KeywordIndex {

    private static final int ALPHABET = 128;

    private final int[] next;
    /** Dla każdego stanu: indeksy reguł, których keyword kończy się w tym stanie (z łańcuchem fail). */
    private final int[][] outputs;

    KeywordIndex(List<List<String>> keywordsPerRule) {
        var gotoTable = new ArrayList<int[]>();
        var outs = new ArrayList<TreeSet<Integer>>();
        addState(gotoTable, outs);
        for (int rule = 0; rule < keywordsPerRule.size(); rule++) {
            for (String keyword : keywordsPerRule.get(rule)) {
                int state = 0;
                for (int i = 0; i < keyword.length() && state >= 0; i++) {
                    char ch = fold(keyword.charAt(i));
                    if (ch >= ALPHABET) {
                        throw new IllegalArgumentException("Keyword spoza ASCII nie jest wspierany: rule #" + rule);
                    }
                    if (gotoTable.get(state)[ch] < 0) {
                        gotoTable.get(state)[ch] = addState(gotoTable, outs);
                    }
                    state = gotoTable.get(state)[ch];
                }
                if (!keyword.isEmpty()) {
                    outs.get(state).add(rule);
                }
            }
        }
        // BFS: funkcja fail i domknięcie przejść do pełnego DFA.
        int[] fail = new int[gotoTable.size()];
        var queue = new ArrayDeque<Integer>();
        int[] root = gotoTable.get(0);
        for (int ch = 0; ch < ALPHABET; ch++) {
            if (root[ch] < 0) {
                root[ch] = 0;
            } else {
                queue.add(root[ch]);
            }
        }
        while (!queue.isEmpty()) {
            int state = queue.poll();
            outs.get(state).addAll(outs.get(fail[state]));
            int[] row = gotoTable.get(state);
            for (int ch = 0; ch < ALPHABET; ch++) {
                int target = row[ch];
                if (target < 0) {
                    row[ch] = gotoTable.get(fail[state])[ch];
                } else {
                    fail[target] = gotoTable.get(fail[state])[ch];
                    queue.add(target);
                }
            }
        }
        next = new int[gotoTable.size() * ALPHABET];
        for (int state = 0; state < gotoTable.size(); state++) {
            System.arraycopy(gotoTable.get(state), 0, next, state * ALPHABET, ALPHABET);
        }
        outputs = outs.stream().map(set -> set.stream().mapToInt(Integer::intValue).toArray()).toArray(int[][]::new);
    }

    int states() {
        return outputs.length;
    }

    /** Stan po przeczytaniu znaku {@code ch} ze stanu {@code state}. */
    int step(int state, char ch) {
        char folded = fold(ch);
        return folded < ALPHABET ? next[state * ALPHABET + folded] : 0;
    }

    /** Reguły, których keyword kończy się w stanie {@code state}; pusta tablica, gdy żadna. */
    int[] outputs(int state) {
        return outputs[state];
    }

    private static char fold(char ch) {
        return ch >= 'A' && ch <= 'Z' ? (char) (ch + ('a' - 'A')) : ch;
    }

    private static int addState(List<int[]> gotoTable, List<TreeSet<Integer>> outs) {
        int[] row = new int[ALPHABET];
        Arrays.fill(row, -1);
        gotoTable.add(row);
        outs.add(new TreeSet<>());
        return gotoTable.size() - 1;
    }
}
