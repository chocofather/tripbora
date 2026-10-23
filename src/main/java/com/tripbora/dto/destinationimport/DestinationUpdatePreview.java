package com.tripbora.dto.destinationimport;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;
import java.util.Set;

public record DestinationUpdatePreview(List<DestinationImportIssue> fileErrors, List<Row> rows, String previewToken) {
    public DestinationUpdatePreview(List<DestinationImportIssue> fileErrors, List<Row> rows) {
        this(fileErrors, rows, null);
    }
    public record Request(String json, List<Selection> selections, String previewToken) {
        public Request(String json, List<Selection> selections) { this(json, selections, null); }
        public Request { selections = selections == null ? List.of() : List.copyOf(selections); }
    }
    public record Selection(int index, Long destinationId, boolean confirmedMatch, Set<String> fields,
                            boolean replaceRelations) {
        public Selection { fields = fields == null ? null : Set.copyOf(fields); }
        public static Selection empty(int index) { return new Selection(index, null, false, null, false); }
    }
    public record Row(int index, String key, String name, String status, String message,
                      Long destinationId, String contentId, List<Candidate> candidates,
                      List<Change> changes, String token, List<String> warnings,
                      List<DestinationImportItem.Evidence> evidence) { }
    public record Candidate(Long destinationId, String name, String message) { }
    public record Change(String path, String label, @JsonIgnore Object before, @JsonIgnore Object after, String beforeText,
                         String afterText, boolean selected, boolean changed, String kind,
                         int addedCount, int removedCount) {
        public Change(String path, String label, Object before, Object after, String beforeText,
                      String afterText, boolean selected, boolean changed, String kind) {
            this(path, label, before, after, beforeText, afterText, selected, changed, kind, 0, 0);
        }
        public Change(String path, String label, Object before, Object after, String beforeText,
                      String afterText, boolean selected, boolean changed) {
            this(path, label, before, after, beforeText, afterText, selected, changed,
                    changed ? "REPLACE" : "UNCHANGED", 0, 0);
        }
    }
    public record ApplyRequest(String token, boolean confirmed, boolean acknowledgeWarnings,
                               boolean confirmRelationRemoval) { }
    public record StatusRequest(String token) { }
    public record Result(String status, String message, Long destinationId, int changedFields) { }
}
