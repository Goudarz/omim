package com.mapswithme.maps.editor.data;

import android.content.res.Resources;
import android.support.annotation.IntRange;
import android.support.annotation.NonNull;

import java.text.DateFormatSymbols;
import java.util.Locale;

import com.mapswithme.maps.MwmApplication;
import com.mapswithme.maps.R;

public class TimeFormatUtils
{
  private TimeFormatUtils() {}

  private static String[] sShortWeekdays;
  private static Locale sCurrentLocale;

  private static void refreshWithCurrentLocale()
  {
    if (!Locale.getDefault().equals(sCurrentLocale))
    {
      sCurrentLocale = Locale.getDefault();
      sShortWeekdays = DateFormatSymbols.getInstance().getShortWeekdays();
      for (int i = 1; i < sShortWeekdays.length; i++)
      {
        sShortWeekdays[i] = Character.toUpperCase(sShortWeekdays[i].charAt(0)) +
                            sShortWeekdays[i].substring(1);
      }
    }
  }

  public static String formatShortWeekday(@IntRange(from = 1, to = 7) int day)
  {
    refreshWithCurrentLocale();
    return sShortWeekdays[day];
  }

  public static String formatWeekdays(@NonNull Timetable timetable)
  {
    refreshWithCurrentLocale();
    final int[] weekdays = timetable.weekdays;
    if (weekdays.length == 0)
      return "";

    final StringBuilder builder = new StringBuilder(sShortWeekdays[weekdays[0]]);
    boolean iteratingRange;
    for (int i = 1; i < weekdays.length; )
    {
      iteratingRange = (weekdays[i] == weekdays[i - 1] + 1);
      if (iteratingRange)
      {
        while (i < weekdays.length && weekdays[i] == weekdays[i - 1] + 1)
          i++;
        builder.append("-").append(sShortWeekdays[weekdays[i - 1]]);
        continue;
      }

      if (i < weekdays.length)
        builder.append(", ").append(sShortWeekdays[weekdays[i]]);

      i++;
    }

    return builder.toString();
  }

  public static String formatTimetables(@NonNull Timetable[] timetables)
  {
    final Resources resources = MwmApplication.get().getResources();

    if (timetables[0].isFullWeek())
    {
      return timetables[0].isFullday ? resources.getString(R.string.twentyfour_seven)
                                     : resources.getString(R.string.daily) + " " + timetables[0].workingTimespan;
    }

    final StringBuilder builder = new StringBuilder();
    for (Timetable tt : timetables)
    {
      String workingTime = tt.isFullday ? resources.getString(R.string.editor_time_allday)
                                        : tt.workingTimespan.toString();

      builder.append(String.format(Locale.getDefault(), "%-21s", formatWeekdays(tt))).append("   ")
             .append(workingTime)
             .append("\n");
    }

    return builder.toString();
  }
}
