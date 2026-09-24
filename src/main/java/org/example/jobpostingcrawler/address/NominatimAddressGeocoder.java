package org.example.jobpostingcrawler.address;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.jobpostingcrawler.config.CrawlerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
public class NominatimAddressGeocoder implements AddressGeocoder {

    private static final Logger log = LoggerFactory.getLogger(NominatimAddressGeocoder.class);
    private static final String[] PLACE_FIELDS = {
            "suburb", "quarter", "city_district", "neighbourhood", "municipality", "town", "village", "city"
    };

    private final CrawlerProperties.GeocodeProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public NominatimAddressGeocoder(CrawlerProperties properties) {
        this(properties.geocode(), HttpClient.newBuilder()
                .connectTimeout(properties.geocode().timeout())
                .build());
    }

    NominatimAddressGeocoder(CrawlerProperties.GeocodeProperties properties, HttpClient httpClient) {
        this.properties = properties;
        this.httpClient = httpClient;
    }

    @Override
    public GeoHit lookup(String query) {
        String url = properties.baseUrl()
                + (properties.baseUrl().contains("?") ? "&" : "?")
                + "format=jsonv2&addressdetails=1&limit=1&countrycodes=vn&q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(properties.timeout())
                .header("User-Agent", properties.userAgent())
                .header("Accept-Language", "vi")
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Nominatim status {} for address lookup", response.statusCode());
                return GeoHit.failed();
            }
            return parse(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("Nominatim lookup interrupted");
            return GeoHit.failed();
        } catch (Exception exception) {
            log.warn("Nominatim lookup failed: {}", exception.toString());
            return GeoHit.failed();
        }
    }

    private GeoHit parse(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        if (!root.isArray() || root.isEmpty()) {
            return GeoHit.none();
        }
        JsonNode address = root.get(0).path("address");
        List<String> places = new ArrayList<>();
        for (String field : PLACE_FIELDS) {
            String value = address.path(field).asText("");
            if (!value.isBlank()) {
                places.add(value);
            }
        }
        String province = firstText(address, "state", "province");
        return new GeoHit(places, province, true);
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
