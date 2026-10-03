package pl.hackyeah.controllayer.guard.secrets;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Jedna reguła z paczki sekretów (np. Gitleaks {@code aws-access-token}), już przetłumaczona na
 * Java regex i przygotowana do szybkiego skanu.
 *
 * @param id               id reguły z paczki, np. {@code github-pat}; trafia do placeholdera i trace
 * @param pattern          wzorzec używany w skanie; dla reguł kontekstowych bez wiodącego prefiksu nazwy zmiennej
 * @param originalPattern  wzorzec 1:1 z paczki (po tłumaczeniu Go→Java); referencja dla testów równoważności
 * @param secretGroup      numer grupy z sekretem; 0 = pierwsza niepusta grupa albo całe dopasowanie
 * @param wholeMatchSecret sekretem jest całe dopasowanie (reguły z nazwanymi grupami-atrybutami)
 * @param minEntropy       minimalna entropia Shannona sekretu; 0 = bez progu
 * @param keywords         keywords prefiltra (lower-case ASCII); pusta lista = reguła zawsze kandydatem
 * @param contextual       reguła o szablonie "nazwa operator wartość" (Gitleaks {@code [\w.-]{0,50}?...})
 * @param bounded          dopasowanie ma ograniczoną długość, więc wystarczy okno wokół keywordu
 * @param allowlists       allowlisty reguły (bez kryteriów ścieżek, które dla promptów nie mają sensu)
 */
public record SecretRule(
        String id,
        String description,
        Pattern pattern,
        Pattern originalPattern,
        int secretGroup,
        boolean wholeMatchSecret,
        double minEntropy,
        List<String> keywords,
        boolean contextual,
        boolean bounded,
        List<Allowlist> allowlists) {

    public SecretRule {
        keywords = List.copyOf(keywords);
        allowlists = List.copyOf(allowlists);
    }
}
