package com.wcdk.r2dbc;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class ReviewTraceabilityTests {
    @Test void allEngineeringFindingsHaveForwardAndReverseEvidenceLinks() throws Exception {
        Map<String,Object> document=new org.yaml.snakeyaml.Yaml().load(Files.readString(Path.of("docs/review-traceability-2026-10-14.json")));
        var rows=(List<?>)document.get("findings");assertThat(rows).hasSize(79);
        Set<String> ids=new HashSet<>();Map<String,Integer> counts=new HashMap<>();
        for(Object item:rows){
            var row=(Map<?,?>)item;assertThat(ids.add((String)row.get("id"))).isTrue();
            counts.merge((String)row.get("priority"),1,Integer::sum);
            for(String field:List.of("code","tests")) {
                var links=(List<?>)row.get(field);assertThat(links).isNotEmpty();
                for(Object link:links)assertThat(Path.of(link.toString())).as(row.get("id").toString()).exists();
            }
            assertThat(Path.of(row.get("documentation").toString())).exists();
            assertThat(row.get("task").toString()).isNotBlank();
        }
        assertThat(counts).isEqualTo(Map.of("P1",12,"P2",24,"P3",43));
    }
}
