package com.brainserve.appointment.worktask.api;
import com.brainserve.appointment.shared.api.SearchContract;
import java.util.UUID;
public interface WorkTaskSearch {
    SearchContract.Group search(UUID actor, String query, int page, int size);
    SearchContract.OpenRecord open(UUID actor, UUID id);
}
