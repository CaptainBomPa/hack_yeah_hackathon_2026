package pl.hackyeah.controllayer.policy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Serializacja polityki: kanoniczny JSON (do bazy i hasha) oraz YAML (eksport/import dla jury).
 * YAML jest czytany wyłącznie przez {@link SafeConstructor} — bez tagów typu {@code !!java/...},
 * czyli bez niebezpiecznej deserializacji (docs/deterministic/23-unsafe-deserialization.md).
 */
public final class PolicyJson {

    private static final JsonMapper CANONICAL = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .build();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private PolicyJson() {
    }

    public static String toJson(PolicyDocument document) {
        return CANONICAL.writeValueAsString(document);
    }

    public static PolicyDocument fromJson(String json) {
        return CANONICAL.readValue(json, PolicyDocument.class);
    }

    /** SHA-256 kanonicznego JSON — ten sam stan polityki zawsze daje ten sam hash. */
    public static String hash(PolicyDocument document) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(toJson(document).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String toYaml(PolicyDocument document) {
        var options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        options.setPrettyFlow(true);
        Map<String, Object> tree = CANONICAL.convertValue(document, MAP);
        return new Yaml(new SafeConstructor(new LoaderOptions()), new Representer(options), options)
                .dump(Map.of("policy", tree));
    }

    /** Akceptuje dokument z kluczem {@code policy:} na górze albo bez niego. */
    public static PolicyDocument fromYaml(String yaml) {
        Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        if (!(root instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("YAML must be a mapping with the policy fields");
        }
        Object policy = map.containsKey("policy") ? map.get("policy") : map;
        return CANONICAL.convertValue(policy, PolicyDocument.class);
    }
}
