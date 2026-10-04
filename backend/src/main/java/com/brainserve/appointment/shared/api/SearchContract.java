package com.brainserve.appointment.shared.api;

import com.brainserve.appointment.shared.application.BusinessException;
import org.springframework.http.HttpStatus;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Bounded, literal queries and safe, domain-approved read projections. */
public final class SearchContract {
    private SearchContract() {}
    public static String query(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.length() < 2 || normalized.length() > 100 || normalized.codePoints().anyMatch(Character::isISOControl))
            throw new BusinessException("SEARCH_QUERY_INVALID", "Enter between 2 and 100 characters", HttpStatus.BAD_REQUEST);
        return normalized;
    }
    public static void bounds(int page, int size) {
        if (page < 0 || page > 999 || size < 1 || size > 25)
            throw new BusinessException("SEARCH_PAGE_INVALID", "Search page or size is outside the supported range", HttpStatus.BAD_REQUEST);
    }
    public static String literal(String query) {
        return "%" + query.toLowerCase(java.util.Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
    public static BusinessException notFound() {
        return new BusinessException("SEARCH_RECORD_NOT_FOUND", "This record is unavailable in your current workspace", HttpStatus.NOT_FOUND);
    }
    public record Item(UUID id, String title, String subtitle, String status) {}
    public record Group(String type, String label, boolean available, String coverage, int number, int size,
                        long totalElements, int totalPages, List<Item> items) {
        public static Group unavailable(String type, String label, String coverage, int page, int size) {
            return new Group(type, label, false, coverage, page, size, 0, 0, List.of());
        }
    }
    public record OpenRecord(String type, UUID id, String title, String subtitle, String status,
                             String route, Map<String, String> detail) {}
    public record Response(String query, List<Group> groups) {}
}
