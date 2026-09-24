package org.example.jobpostingcrawler.address;

import java.util.List;

public interface AddressGeocoder {

    GeoHit lookup(String query);

    record GeoHit(List<String> places, String provinceText, boolean definitive) {
        static GeoHit failed() {
            return new GeoHit(List.of(), "", false);
        }

        static GeoHit none() {
            return new GeoHit(List.of(), "", true);
        }
    }
}
