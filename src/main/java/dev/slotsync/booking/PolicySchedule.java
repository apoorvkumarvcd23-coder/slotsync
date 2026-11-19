package dev.slotsync.booking;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.IntStream;

/** One UTC daily window on selected ISO weekdays; closing is exclusive. */
record PolicySchedule(int horizonDays,List<Integer> openingDays,String opensAt,String closesAt) {
    PolicySchedule {
        if(horizonDays<1 || horizonDays>365 || openingDays==null || openingDays.isEmpty() || openingDays.size()>7
                || openingDays.stream().anyMatch(d->d==null || d<1 || d>7)
                || openingDays.stream().distinct().count()!=openingDays.size())
            throw invalid("Use a 1–365 day horizon and distinct weekdays from 1 (Monday) to 7 (Sunday).");
        openingDays=openingDays.stream().sorted().toList();
        if(minute(opensAt,false)>=minute(closesAt,true))
            throw invalid("Opening must precede closing; overnight windows are not supported.");
    }
    private static BookingService.BookingError invalid(String message) {
        return new BookingService.BookingError(400,"INVALID_POLICY",message);
    }
    private static int minute(String value,boolean closing) {
        if(closing && "24:00".equals(value)) return 1440;
        if(value==null || !value.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]"))
            throw invalid("Use UTC HH:mm times; only closing may be 24:00.");
        return Integer.parseInt(value.substring(0,2))*60+Integer.parseInt(value.substring(3));
    }
    int openMinute() {return minute(opensAt,false);}
    int closeMinute() {return minute(closesAt,true);}
    int mask() {return openingDays.stream().mapToInt(d->1<<(d-1)).reduce(0,(a,b)->a|b);}
    static List<Integer> days(int mask) {return IntStream.rangeClosed(1,7).filter(d->(mask&(1<<(d-1)))!=0).boxed().toList();}
    static String time(int minute) {return String.format(java.util.Locale.ROOT,"%02d:%02d",minute/60,minute%60);}

    void check(Instant start,Instant end,Instant now) {
        if(start.isAfter(now.plus(horizonDays,ChronoUnit.DAYS)))
            throw new BookingService.BookingError(400,"HORIZON_EXCEEDED","Start must be within the current "+horizonDays+"-day booking horizon.");
        // Check every occupied UTC date. An exclusive midnight end occupies only the preceding date.
        for(Instant cursor=start;cursor.isBefore(end);) {
            var date=cursor.atOffset(ZoneOffset.UTC).toLocalDate();
            Instant midnight=date.atStartOfDay().toInstant(ZoneOffset.UTC);
            Instant next=midnight.plus(1,ChronoUnit.DAYS);
            Instant partEnd=end.isBefore(next)?end:next;
            if(!openingDays.contains(date.getDayOfWeek().getValue())
                    || cursor.isBefore(midnight.plus(openMinute(),ChronoUnit.MINUTES))
                    || partEnd.isAfter(midnight.plus(closeMinute(),ChronoUnit.MINUTES)))
                throw new BookingService.BookingError(400,"OUTSIDE_OPENING_HOURS","Choose an interval entirely within the current UTC opening days and hours.");
            cursor=partEnd;
        }
    }
}
