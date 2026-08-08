package io.stellasora.server.protocol;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds a categorized machine-readable implementation matrix for recovered requests. */
public final class ProtocolCoverage {
    private ProtocolCoverage() {}

    public static Snapshot snapshot(
            MessageCatalog catalog, Map<Integer, String> implementations) {
        List<RequestCoverage> requests = new ArrayList<>();
        Map<String, MutableCategory> categories = new LinkedHashMap<>();
        int implemented = 0;
        int typed = 0;

        for (MessageCatalog.Entry request : catalog.entries()) {
            if (!"client_to_server".equals(request.direction())
                    || !"request".equals(request.role())) {
                continue;
            }
            MessageCatalog.Entry response = catalog.successFor(request).orElse(null);
            String implementation = implementations.getOrDefault(request.id(), "fallback_empty");
            boolean isImplemented = !"fallback_empty".equals(implementation);
            if (isImplemented) {
                implemented++;
            }
            if ("typed_java".equals(implementation)) {
                typed++;
            }
            MutableCategory category = categories.computeIfAbsent(
                    request.category(), MutableCategory::new);
            category.total++;
            if (isImplemented) {
                category.implemented++;
            }
            requests.add(new RequestCoverage(
                    request.id(),
                    request.name(),
                    request.protobufType(),
                    response == null ? null : response.id(),
                    response == null ? null : response.name(),
                    response == null ? null : response.protobufType(),
                    request.category(),
                    implementation));
        }

        requests.sort(Comparator.comparingInt(RequestCoverage::requestId));
        List<CategoryCoverage> categoryRows = categories.values().stream()
                .sorted(Comparator.comparing(category -> category.name))
                .map(category -> new CategoryCoverage(
                        category.name,
                        category.total,
                        category.implemented,
                        category.total - category.implemented))
                .toList();
        Summary summary = new Summary(
                catalog.size(),
                requests.size(),
                implemented,
                typed,
                requests.size() - implemented);
        return new Snapshot(summary, categoryRows, List.copyOf(requests));
    }

    public record Snapshot(
            Summary summary,
            List<CategoryCoverage> categories,
            List<RequestCoverage> requests) {}

    public record Summary(
            int totalMessages,
            int totalRequests,
            int implementedRequests,
            int typedJavaRequests,
            int fallbackRequests) {}

    public record CategoryCoverage(
            String category, int totalRequests, int implementedRequests, int fallbackRequests) {}

    public record RequestCoverage(
            int requestId,
            String requestName,
            String requestProtobufType,
            Integer responseId,
            String responseName,
            String responseProtobufType,
            String category,
            String implementation) {}

    private static final class MutableCategory {
        private final String name;
        private int total;
        private int implemented;

        private MutableCategory(String name) {
            this.name = name;
        }
    }
}
