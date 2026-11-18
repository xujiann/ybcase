package cn.ybcase.bureau;

import cn.ybcase.bureau.common.Workdays;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class Workdays2027Test {
    // Independently transcribed from 国办发明电〔2026〕10号, published 2026-11-05.
    // https://www.gov.cn/zhengce/content/202611/content_7085101.htm
    private static final Set<LocalDate> OFFICIAL_HOLIDAYS = dates(
            "2027-01-01", "2027-01-02", "2027-01-03",
            "2027-02-05", "2027-02-06", "2027-02-07", "2027-02-08", "2027-02-09",
            "2027-02-10", "2027-02-11", "2027-02-12", "2027-02-13",
            "2027-04-03", "2027-04-04", "2027-04-05",
            "2027-05-01", "2027-05-02", "2027-05-03", "2027-05-04", "2027-05-05",
            "2027-06-09", "2027-09-15",
            "2027-10-01", "2027-10-02", "2027-10-03", "2027-10-04",
            "2027-10-05", "2027-10-06", "2027-10-07");
    private static final Set<LocalDate> OFFICIAL_SHIFT_DAYS = dates(
            "2027-01-31", "2027-02-14", "2027-05-08", "2027-09-26", "2027-10-09");
    private static Set<LocalDate> holidays;
    private static Set<LocalDate> shiftDays;

    @BeforeAll
    static void readPackagedMigration() throws IOException {
        String resource = "/db/migration/V67__holiday_2027_calendar.sql";
        try (var input = Workdays2027Test.class.getResourceAsStream(resource)) {
            assertNotNull(input, "The official calendar must be packaged with the application");
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            var row = Pattern.compile("\\(\\s*'(\\d{4}-\\d{2}-\\d{2})'\\s*,\\s*'([^']+)'\\s*,\\s*'[^']*'\\s*\\)")
                    .matcher(sql);
            Map<LocalDate, String> calendar = new HashMap<>();
            while (row.find()) {
                LocalDate day = LocalDate.parse(row.group(1));
                String kind = row.group(2);
                assertTrue(Set.of("HOLIDAY", "SHIFT_WORK").contains(kind), "Unknown calendar kind on " + day);
                assertNull(calendar.put(day, kind), "Duplicate calendar date " + day);
            }
            holidays = calendar.entrySet().stream().filter(entry -> "HOLIDAY".equals(entry.getValue()))
                    .map(Map.Entry::getKey).collect(Collectors.toUnmodifiableSet());
            shiftDays = calendar.entrySet().stream().filter(entry -> "SHIFT_WORK".equals(entry.getValue()))
                    .map(Map.Entry::getKey).collect(Collectors.toUnmodifiableSet());
        }
    }

    @Test
    void packagedDatesMatchAllOfficialHolidaysAndShiftDays() {
        assertEquals(29, holidays.size());
        assertEquals(5, shiftDays.size());
        assertEquals(OFFICIAL_HOLIDAYS, holidays);
        assertEquals(OFFICIAL_SHIFT_DAYS, shiftDays);
        assertTrue(holidays.stream().noneMatch(shiftDays::contains));
    }

    @ParameterizedTest
    @MethodSource("officialHolidayDates")
    void everyOfficialHolidayIsNonWorking(LocalDate day) {
        assertFalse(Workdays.isWorkingDay(day, holidays, shiftDays));
    }

    static Stream<LocalDate> officialHolidayDates() {
        return OFFICIAL_HOLIDAYS.stream().sorted();
    }

    @ParameterizedTest
    @MethodSource("officialShiftDates")
    void everyOfficialWeekendShiftIsWorking(LocalDate day) {
        assertTrue(day.getDayOfWeek().getValue() >= 6);
        assertTrue(Workdays.isWorkingDay(day, holidays, shiftDays));
    }

    static Stream<LocalDate> officialShiftDates() {
        return OFFICIAL_SHIFT_DAYS.stream().sorted();
    }

    @Test
    void fullYearContains248WorkingDays() {
        LocalDate first = LocalDate.of(2027, 1, 1);
        long actual = IntStream.range(0, first.lengthOfYear()).mapToObj(first::plusDays)
                .filter(day -> Workdays.isWorkingDay(day, holidays, shiftDays)).count();
        assertEquals(248, actual);
    }

    @ParameterizedTest
    @MethodSource("deadlineCases")
    void deadlinesSkipOfficialBreaksAndIncludeWeekendShifts(String started, int days, String expected) {
        assertEquals(LocalDate.parse(expected), Workdays.plus(LocalDate.parse(started), days, holidays, shiftDays));
    }

    static Stream<Arguments> deadlineCases() {
        return Stream.of(
                Arguments.of("2026-12-31", 1, "2027-01-04"),
                Arguments.of("2026-12-24", 7, "2027-01-05"),
                Arguments.of("2026-12-18", 15, "2027-01-11"),
                Arguments.of("2027-01-29", 1, "2027-01-31"),
                Arguments.of("2027-01-31", 5, "2027-02-14"),
                Arguments.of("2027-02-04", 1, "2027-02-14"),
                Arguments.of("2027-02-04", 7, "2027-02-22"),
                Arguments.of("2027-02-04", 10, "2027-02-25"),
                Arguments.of("2027-02-04", 15, "2027-03-04"),
                Arguments.of("2027-04-02", 1, "2027-04-06"),
                Arguments.of("2027-04-30", 1, "2027-05-06"),
                Arguments.of("2027-04-30", 3, "2027-05-08"),
                Arguments.of("2027-05-07", 1, "2027-05-08"),
                Arguments.of("2027-06-08", 1, "2027-06-10"),
                Arguments.of("2027-09-14", 1, "2027-09-16"),
                Arguments.of("2027-09-24", 1, "2027-09-26"),
                Arguments.of("2027-09-30", 1, "2027-10-08"),
                Arguments.of("2027-10-08", 1, "2027-10-09"));
    }

    @Test
    void ordinaryWeekdaysAndUnadjustedWeekendsKeepTheirMeaning() {
        assertTrue(Workdays.isWorkingDay(LocalDate.of(2027, 1, 29), holidays, shiftDays));
        assertFalse(Workdays.isWorkingDay(LocalDate.of(2027, 1, 30), holidays, shiftDays));
        assertFalse(Workdays.isWorkingDay(LocalDate.of(2027, 2, 20), holidays, shiftDays));
        assertFalse(Workdays.isWorkingDay(LocalDate.of(2027, 2, 21), holidays, shiftDays));
    }

    @Test
    void zeroWorkingDaysPreserveTheStartingDayEvenDuringABreak() {
        LocalDate holiday = LocalDate.of(2027, 2, 5);
        assertEquals(holiday, Workdays.plus(holiday, 0, holidays, shiftDays));
    }

    private static Set<LocalDate> dates(String... days) {
        return Arrays.stream(days).map(LocalDate::parse).collect(Collectors.toUnmodifiableSet());
    }
}
