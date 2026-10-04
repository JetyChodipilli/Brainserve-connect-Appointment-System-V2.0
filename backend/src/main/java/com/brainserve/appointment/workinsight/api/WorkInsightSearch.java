package com.brainserve.appointment.workinsight.api;
import com.brainserve.appointment.shared.api.SearchContract;
import java.util.UUID;
public interface WorkInsightSearch {
    SearchContract.Group search(UUID actor,String query,int page,int size);
    SearchContract.OpenRecord open(UUID actor,UUID id);
}
