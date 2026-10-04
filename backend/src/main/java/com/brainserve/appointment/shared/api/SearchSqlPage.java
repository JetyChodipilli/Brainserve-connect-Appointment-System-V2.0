package com.brainserve.appointment.shared.api;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import java.util.List;
import java.util.UUID;

/** One bounded relation supplies both its authorized count and page in the same database snapshot. */
public final class SearchSqlPage {
    private SearchSqlPage() {}
    public static SearchContract.Group read(NamedParameterJdbcTemplate jdbc, String type, String label, String coverage,
            String relation, MapSqlParameterSource parameters, int page, int size) {
        SearchContract.bounds(page, size);
        parameters.addValue("offset", page * size).addValue("limit", size);
        var rows = jdbc.query("with matches as materialized (" + relation + "), totals as (select count(*) total from matches), "
                + "paged as (select * from matches order by lower(title),id limit :limit offset :offset) "
                + "select totals.total,paged.* from totals left join paged on true order by lower(paged.title),paged.id", parameters,
                (rs, n) -> new Row(rs.getLong("total"), rs.getObject("id") == null ? null : new SearchContract.Item(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("subtitle"), rs.getString("status"))));
        long total = rows.getFirst().total();
        return new SearchContract.Group(type, label, true, coverage, page, size, total, (int) ((total + size - 1) / size), rows.stream().map(Row::item).filter(java.util.Objects::nonNull).toList());
    }
    private record Row(long total, SearchContract.Item item) {}
}
