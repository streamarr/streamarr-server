# Third-Party Notices

This repository contains adapted third-party test fixture data. The notices below apply only to the identified material. Streamarr's modifications and all other project material remain subject to the project license in `LICENSE` unless stated otherwise. No upstream creator or project endorses Streamarr or its use of the material.

## UTF-8 decoder capability and stress test

The following files contain data adapted from *UTF-8 decoder capability and stress test*:

- `src/test/resources/filepath-codec/malformed-utf8.csv`
- `src/test/resources/filepath-codec/valid-utf8-boundaries.csv`

Original attribution:

> UTF-8 decoder capability and stress test
> Markus Kuhn <http://www.cl.cam.ac.uk/~mgk25/> - 2015-08-28 - CC BY 4.0

Source: <https://www.cl.cam.ac.uk/~mgk25/ucs/examples/UTF-8-test.txt>

License: Creative Commons Attribution 4.0 International. The complete license text is in `src/test/resources/filepath-codec/licenses/CC-BY-4.0.txt`; the canonical license URI is <https://creativecommons.org/licenses/by/4.0/>.

Modifications by Streamarr contributors: selected malformed and boundary sequences; split compound cases; renamed cases; converted bytes to uppercase `%HH` URI escapes; and represented expected code points as hexadecimal values. The original material remains available under CC BY 4.0.

## Eclipse Jetty

`src/test/resources/filepath-codec/malformed-percent-escapes.csv` contains selected data adapted from Eclipse Jetty's `URIUtilTest`.

Copyright notice retained from the source file:

> Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.

Attribution retained from Eclipse Jetty's `NOTICE.txt`:

> This content is produced and maintained by the Eclipse Jetty project.
> Project home: <https://jetty.org/>

Eclipse Jetty and Jetty are trademarks of the Eclipse Foundation.

Source: <https://github.com/jetty/jetty.project/blob/2812785776c320d511ab382d2cdc25cf6cf47b29/jetty-core/jetty-util/src/test/java/org/eclipse/jetty/util/URIUtilTest.java>

Jetty offers this material under `EPL-2.0 OR Apache-2.0`. Streamarr elects the Apache License 2.0 option. The complete license text is in `src/test/resources/filepath-codec/licenses/Apache-2.0.txt`; the canonical license URI is <https://www.apache.org/licenses/LICENSE-2.0>.

Modifications by Streamarr contributors: selected and deduplicated inputs; renamed cases; represented inputs as filepath URI suffixes; and supplied assertions for Streamarr's strict-rejection contract rather than Jetty's behavior.

## Spring Framework

`src/test/resources/filepath-codec/malformed-percent-escapes.csv` also contains selected data adapted from Spring Framework's `UriUtilsTests`.

Copyright notice retained from the source file:

> Copyright 2002-present the original author or authors.

Authors identified by the source file: Arjen Poutsma, Juergen Hoeller, and Med Belamachi.

Source: <https://github.com/spring-projects/spring-framework/blob/e8729d043887bf0d0baf91e062e909b56eb2b708/spring-web/src/test/java/org/springframework/web/util/UriUtilsTests.java>

License: Apache License 2.0. The complete license text is in `src/test/resources/filepath-codec/licenses/Apache-2.0.txt`; the canonical license URI is <https://www.apache.org/licenses/LICENSE-2.0>.

Modifications by Streamarr contributors: selected and deduplicated inputs; renamed cases; represented inputs as filepath URI suffixes; and supplied assertions for Streamarr's strict-rejection contract rather than Spring Framework's behavior.
