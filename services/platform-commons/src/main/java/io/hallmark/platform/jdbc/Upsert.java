package io.hallmark.platform.jdbc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class Upsert {
    private Upsert() {}

    public static void row(JdbcClient jdbc, String table, List<String> keyColumns, Map<String, ?> values) {
        Map<String, Object> params = new LinkedHashMap<>(values);
        String setClause = values.keySet().stream()
                .filter(c -> !keyColumns.contains(c))
                .map(c -> c + " = :" + c)
                .collect(Collectors.joining(", "));
        String where = keyColumns.stream().map(c -> c + " = :" + c).collect(Collectors.joining(" and "));
        int updated = setClause.isEmpty()
                ? jdbc.sql("select count(*) from " + table + " where " + where)
                        .params(params)
                        .query(Integer.class)
                        .single()
                : jdbc.sql("update " + table + " set " + setClause + " where " + where)
                        .params(params)
                        .update();
        if (updated > 0) {
            return;
        }
        String columns = String.join(", ", values.keySet());
        String placeholders = values.keySet().stream().map(c -> ":" + c).collect(Collectors.joining(", "));
        try {
            jdbc.sql("insert into " + table + " (" + columns + ") values (" + placeholders + ")")
                    .params(params)
                    .update();
        } catch (DuplicateKeyException race) {
            if (!setClause.isEmpty()) {
                jdbc.sql("update " + table + " set " + setClause + " where " + where)
                        .params(params)
                        .update();
            }
        }
    }
}
