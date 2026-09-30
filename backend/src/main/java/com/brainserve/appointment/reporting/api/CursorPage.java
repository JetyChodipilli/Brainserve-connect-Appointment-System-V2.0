package com.brainserve.appointment.reporting.api;

import java.util.List;

public record CursorPage<T>(List<T> items, String nextCursor, boolean hasMore, int size) {}
