package com.gathin.tvhome;

import java.time.Instant;
import java.time.ZoneId;

/**
 * Roughly where the TV is, from its time zone alone, for the sun's times when config.xml has no latitude and
 * longitude (tvos27's Automatic appearance). No location permission and no network: a zone's main city for the
 * common zones, else the longitude of the zone's standard offset and a typical latitude for its continent. Off by
 * a few hundred kilometres moves sunrise by minutes, which is plenty for choosing light or dark. Pure Java.
 */
final class ZonePlace {

    private ZonePlace() {
    }

    /** Zone id, latitude, longitude and country: the cities the most-used zones are named after. */
    private static final Object[][] CITIES = {
            {"America/New_York", 40.71, -74.01, "US"}, {"America/Chicago", 41.88, -87.63, "US"},
            {"America/Denver", 39.74, -104.99, "US"}, {"America/Phoenix", 33.45, -112.07, "US"},
            {"America/Los_Angeles", 34.05, -118.24, "US"}, {"America/Anchorage", 61.22, -149.90, "US"},
            {"Pacific/Honolulu", 21.31, -157.86, "US"}, {"America/Toronto", 43.65, -79.38, "CA"},
            {"America/Vancouver", 49.28, -123.12, "CA"}, {"America/Mexico_City", 19.43, -99.13, "MX"},
            {"America/Sao_Paulo", -23.55, -46.63, "BR"}, {"America/Argentina/Buenos_Aires", -34.60, -58.38, "AR"},
            {"America/Bogota", 4.71, -74.07, "CO"}, {"America/Lima", -12.05, -77.04, "PE"}, {"America/Santiago", -33.45, -70.67, "CL"},
            {"Europe/London", 51.51, -0.13, "GB"}, {"Europe/Dublin", 53.35, -6.26, "IE"}, {"Europe/Lisbon", 38.72, -9.14, "PT"},
            {"Europe/Madrid", 40.42, -3.70, "ES"}, {"Europe/Paris", 48.86, 2.35, "FR"}, {"Europe/Berlin", 52.52, 13.40, "DE"},
            {"Europe/Rome", 41.90, 12.50, "IT"}, {"Europe/Amsterdam", 52.37, 4.90, "NL"}, {"Europe/Stockholm", 59.33, 18.07, "SE"},
            {"Europe/Oslo", 59.91, 10.75, "NO"}, {"Europe/Helsinki", 60.17, 24.94, "FI"}, {"Europe/Warsaw", 52.23, 21.01, "PL"},
            {"Europe/Vilnius", 54.69, 25.28, "LT"}, {"Europe/Kyiv", 50.45, 30.52, "UA"}, {"Europe/Kiev", 50.45, 30.52, "UA"},
            {"Europe/Istanbul", 41.01, 28.98, "TR"}, {"Europe/Moscow", 55.76, 37.62, "RU"}, {"Europe/Athens", 37.98, 23.73, "GR"},
            {"Africa/Cairo", 30.04, 31.24, "EG"}, {"Africa/Lagos", 6.52, 3.38, "NG"}, {"Africa/Johannesburg", -26.20, 28.05, "ZA"},
            {"Africa/Nairobi", -1.29, 36.82, "KE"}, {"Asia/Dubai", 25.20, 55.27, "AE"}, {"Asia/Tehran", 35.69, 51.39, "IR"},
            {"Asia/Karachi", 24.86, 67.01, "PK"}, {"Asia/Kolkata", 22.57, 88.36, "IN"}, {"Asia/Calcutta", 22.57, 88.36, "IN"},
            {"Asia/Dhaka", 23.81, 90.41, "BD"}, {"Asia/Bangkok", 13.76, 100.50, "TH"}, {"Asia/Ho_Chi_Minh", 10.82, 106.63, "VN"},
            {"Asia/Jakarta", -6.21, 106.85, "ID"}, {"Asia/Singapore", 1.35, 103.82, "SG"}, {"Asia/Shanghai", 31.23, 121.47, "CN"},
            {"Asia/Hong_Kong", 22.32, 114.17, "HK"}, {"Asia/Taipei", 25.03, 121.57, "TW"}, {"Asia/Seoul", 37.57, 126.98, "KR"},
            {"Asia/Tokyo", 35.68, 139.69, "JP"}, {"Asia/Manila", 14.60, 120.98, "PH"}, {"Australia/Sydney", -33.87, 151.21, "AU"},
            {"Australia/Melbourne", -37.81, 144.96, "AU"}, {"Australia/Brisbane", -27.47, 153.03, "AU"},
            {"Australia/Perth", -31.95, 115.86, "AU"}, {"Pacific/Auckland", -36.85, 174.76, "NZ"},
    };

    /**
     * The country of a common zone ("America/New_York" is US), or null. On the TV Tmdb asks ICU first, which knows
     * every zone; this covers the JVM tests and a build without ICU's zone data.
     */
    static String country(String zoneId) {
        for (Object[] c : CITIES) if (c[0].equals(zoneId)) return (String) c[3];
        return null;
    }

    /** {latitude, longitude} for {@code zoneId}, or null for a zone with no place (UTC, GMT, Etc/...). */
    static double[] of(String zoneId) {
        if (zoneId == null || zoneId.isEmpty()) return null;
        for (Object[] c : CITIES) if (c[0].equals(zoneId)) return new double[]{(Double) c[1], (Double) c[2]};
        int slash = zoneId.indexOf('/');
        if (slash < 0 || zoneId.startsWith("Etc/")) return null;
        double lat;
        switch (zoneId.substring(0, slash)) {
            case "America": lat = 30; break;
            case "Europe": lat = 50; break;
            case "Asia": lat = 30; break;
            case "Africa": lat = 5; break;
            case "Australia": lat = -30; break;
            case "Pacific": lat = -15; break;
            case "Atlantic": lat = 35; break;
            case "Indian": lat = -10; break;
            case "Antarctica": lat = -75; break;
            case "Arctic": lat = 78; break;
            default: return null;
        }
        int standardSeconds;
        try {
            ZoneId z = ZoneId.of(zoneId);
            standardSeconds = z.getRules().getStandardOffset(Instant.now()).getTotalSeconds();
        } catch (RuntimeException e) {
            return null;
        }
        double lon = standardSeconds / 240.0;   // 15 degrees an hour
        // UTC+13 and +14 (Tonga, Tokelau, Kiritimati) sit just past the date line, not beyond 180.
        if (lon > 180) lon -= 360;
        if (lon < -180) lon += 360;
        return new double[]{lat, lon};
    }
}
