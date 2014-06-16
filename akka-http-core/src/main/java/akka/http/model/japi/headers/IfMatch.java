/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

/**
 *  Model for the `If-Match` header.
 *  Specification: http://tools.ietf.org/html/draft-ietf-httpbis-p4-conditional-26#section-3.1
 */
public abstract class IfMatch extends akka.http.model.HttpHeader {
    public abstract EntityTagRange m();

    public static IfMatch create(EntityTagRange m) {
        return new akka.http.model.headers.If$minusMatch(((akka.http.model.headers.EntityTagRange) m));
    }
}
