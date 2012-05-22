/* 
 * Copyright (c) 2012 Orderly Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow.hive.serde;

// Java
import java.text.SimpleDateFormat;
import java.text.ParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Hive
import org.apache.hadoop.hive.serde2.SerDeException;

// Java Library for User-Agent Information
import nl.bitwalker.useragentutils.*;

/**
 * SnowPlowEventStruct represents the Hive struct for a SnowPlow event or page view.
 *
 * Contains a parse() method to perform an update-in-place for this instance
 * based on the current row's contents.
 *
 * Constructor is empty because we do updates-in-place for performance reasons.
 */
public class SnowPlowEventStruct {

  // -------------------------------------------------------------------------------------------------------------------
  // Mutable properties for this Hive struct
  // -------------------------------------------------------------------------------------------------------------------

  public String dt;
  public String tm;
  public String user_ipaddress;
  public String page_url;

  // TODO: add in rest of public fields

  public String br_name;
  public String br_group;
  public String br_version;
  public String br_type;

  // -------------------------------------------------------------------------------------------------------------------
  // Static configuration
  // -------------------------------------------------------------------------------------------------------------------

  // Define the regular expression for extracting the fields
  // Adapted from Amazon's own cloudfront-loganalyzer.tgz
  private static final String w = "[\\s]+"; // Whitespace regex
  private static final Pattern cfRegex = Pattern.compile("([\\S]+)"  // Date          / date
                                                   + w + "([\\S]+)"  // Time          / time
                                                   + w + "([\\S]+)"  // EdgeLocation  / x-edge-location
                                                   + w + "([\\S]+)"  // BytesSent     / sc-bytes
                                                   + w + "([\\S]+)"  // IPAddress     / c-ip
                                                   + w + "([\\S]+)"  // Operation     / cs-method
                                                   + w + "([\\S]+)"  // Domain        / cs(Host)
                                                   + w + "([\\S]+)"  // Object        / cs-uri-stem
                                                   + w + "([\\S]+)"  // HttpStatus    / sc-status
                                                   + w + "([\\S]+)"  // Referrer      / cs(Referer)
                                                   + w + "([\\S]+)"  // UserAgent     / cs(User Agent)
                                                   + w + "(.+)");    // Querystring   / cs-uri-query

  // -------------------------------------------------------------------------------------------------------------------
  // Deserialization logic
  // -------------------------------------------------------------------------------------------------------------------

  /**
   * Parses the input row String into a Java object.
   * For performance reasons this works in-place updating the fields
   * within this SnowPlowEventStruct, rather than creating a new one.
   * 
   * @param row The raw String containing the row contents
   * @return This struct with all values updated
   * @throws SerDeException For any exception during parsing
   */
  public Object parse(String row) throws SerDeException {
    
    // We have to handle any header rows
    if (row.startsWith("#Version:") || row.startsWith("#Fields:")) {
      return null; // Empty row will be discarded by Hive
    }

    final Matcher m = cfRegex.matcher(row);
    
    try {
      // Check our row is kosher
      m.matches();

      // 1. First we retrieve the fields which get directly passed through
      this.dt = m.group(1);
      this.tm = m.group(2); // CloudFront date format matches Hive's
      this.user_ipaddress = m.group(5);

      // 2. Now we dis-assemble the user agent
      String ua = m.group(11);
      UserAgent userAgent = UserAgent.parseUserAgentString(ua);

      Browser b = userAgent.getBrowser();
      this.br_name = b.getName();
      this.br_group = b.getGroup().getName();
      this.br_version = b.getVersion(ua).toString(); // No idea why ua needs to be passed back for versioning
      this.br_type = b.getBrowserType().getName();

                 /*
                 ${request.userAgent.browser}
 ${request.userAgent.browser.group}
 ${request.userAgent.browser.manufacturer}
 ${request.userAgent.browser.browserType}
 ${request.userAgent.browser.renderingEngine}

 Operating System:
 ${request.userAgent.operatingSystem}
 ${request.userAgent.operatingSystem.group}
 ${request.userAgent.operatingSystem.manufacturer}
 ${request.userAgent.operatingSystem.deviceType}
 ${request.userAgent.operatingSystem.mobileDevice?string}     */

      // 3. Now we dis-assemble the querystring
      String querystring = m.group(12);
      String qsUrl = null;
      if (isNullField(querystring)) { // No querystring means this row wasn't generated by SnowPlow
        return null; // Skip the row
      } else {
        // TODO first check for hyphen and return null if no querystring
      }

      // 4. Finally construct the page_url
      String cfUrl = m.group(10);
      if (isNullField(cfUrl)) { // CloudFront didn't provide the URL as cs(Referer)
        this.page_url = qsUrl; // Use the querystring URL
      } else {
        this.page_url = cfUrl; // Use the CloudFront URL
      }

    } catch (Exception e) {
      throw new SerDeException("Could not parse row: " + row, e);
    }

    return this; // Return the SnowPlowEventStruct
  }

  // -------------------------------------------------------------------------------------------------------------------
  // Datatype conversions
  // -------------------------------------------------------------------------------------------------------------------

  /**
   * Checks whether a String is a hyphen "-", which
   * is used by CloudFront to signal a null. Package
   * private for unit testing.
   *
   * @param s The String to check
   * @return True if the String was a hyphen "-"
   */
  static boolean isNullField(String s) { return (s == null || s.equals("") || s.equals("-")); }
}
