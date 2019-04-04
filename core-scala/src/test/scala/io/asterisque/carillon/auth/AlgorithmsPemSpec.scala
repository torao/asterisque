package io.asterisque.carillon.auth

import java.util.Base64

import io.asterisque.auth.Algorithms
import org.specs2.Specification

class AlgorithmsPemSpec extends Specification {
  def is =
    s2"""
It can parse multi-entries. $parseMultiEntries
It can return zero-size entries when DER binary specified. $returnZeroLengthEntriesForDER
It can ignore the unmatched begin/end separators. $ignoreUnmatchSeparator
    """

  private[this] def parseMultiEntries = {
    val cert =
      """-----BEGIN EC PRIVATE KEY-----
        |MHcCAQEEIP+G4bF24VHbhiFlSnI61OYvGfb4FRdIH12Ui6WMvvjAoAoGCCqGSM49
        |AwEHoUQDQgAEArZHyz6g/N3pj5n5RVtSouIGthULPAVJVNuzExeTG6tvByYuvCH9
        |6HphborpvZzYCUj6TQ7qe1BHE8gk0bOUSw==
        |-----END EC PRIVATE KEY-----
        |-----BEGIN CERTIFICATE-----
        |MIICOTCCAd+gAwIBAgIJAJBc9fHfC/h0MAoGCCqGSM49BAMCMHkxCzAJBgNVBAYT
        |AkpQMQ4wDAYDVQQIDAVUb2t5bzEPMA0GA1UEBwwGU3VtaWRhMREwDwYDVQQKDAhD
        |YXJpbGxvbjESMBAGA1UECwwJVW5pdCBUZXN0MSIwIAYDVQQDDBljYS5jYXJpbGxv
        |bi5hc3RlcmlzcXVlLmlvMB4XDTE5MDIxMTE4MjY0NloXDTIwMDIxMTE4MjY0Nlow
        |eTELMAkGA1UEBhMCSlAxDjAMBgNVBAgMBVRva3lvMQ8wDQYDVQQHDAZTdW1pZGEx
        |ETAPBgNVBAoMCENhcmlsbG9uMRIwEAYDVQQLDAlVbml0IFRlc3QxIjAgBgNVBAMM
        |GWNhLmNhcmlsbG9uLmFzdGVyaXNxdWUuaW8wWTATBgcqhkjOPQIBBggqhkjOPQMB
        |BwNCAAQCtkfLPqD83emPmflFW1Ki4ga2FQs8BUlU27MTF5Mbq28HJi68If3oemFu
        |ium9nNgJSPpNDup7UEcTyCTRs5RLo1AwTjAdBgNVHQ4EFgQUVACN213GZHtkJVem
        |/W3y2vOfE8owHwYDVR0jBBgwFoAUVACN213GZHtkJVem/W3y2vOfE8owDAYDVR0T
        |BAUwAwEB/zAKBggqhkjOPQQDAgNIADBFAiAvFveO6PaT/tTnEfR9wiQG3TnNukgr
        |Hj0WaRtgCBAuJwIhAOwmTuPS8+KqO3rkDzQYhwcCmD+AIpC2Jfx3TT5WdLwK
        |-----END CERTIFICATE-----
        |""".stripMargin
    val expected = Seq(
      "EC PRIVATE KEY" ->
        """MHcCAQEEIP+G4bF24VHbhiFlSnI61OYvGfb4FRdIH12Ui6WMvvjAoAoGCCqGSM49
          |AwEHoUQDQgAEArZHyz6g/N3pj5n5RVtSouIGthULPAVJVNuzExeTG6tvByYuvCH9
          |6HphborpvZzYCUj6TQ7qe1BHE8gk0bOUSw==""".stripMargin,
      "CERTIFICATE" ->
        """MIICOTCCAd+gAwIBAgIJAJBc9fHfC/h0MAoGCCqGSM49BAMCMHkxCzAJBgNVBAYT
          |AkpQMQ4wDAYDVQQIDAVUb2t5bzEPMA0GA1UEBwwGU3VtaWRhMREwDwYDVQQKDAhD
          |YXJpbGxvbjESMBAGA1UECwwJVW5pdCBUZXN0MSIwIAYDVQQDDBljYS5jYXJpbGxv
          |bi5hc3RlcmlzcXVlLmlvMB4XDTE5MDIxMTE4MjY0NloXDTIwMDIxMTE4MjY0Nlow
          |eTELMAkGA1UEBhMCSlAxDjAMBgNVBAgMBVRva3lvMQ8wDQYDVQQHDAZTdW1pZGEx
          |ETAPBgNVBAoMCENhcmlsbG9uMRIwEAYDVQQLDAlVbml0IFRlc3QxIjAgBgNVBAMM
          |GWNhLmNhcmlsbG9uLmFzdGVyaXNxdWUuaW8wWTATBgcqhkjOPQIBBggqhkjOPQMB
          |BwNCAAQCtkfLPqD83emPmflFW1Ki4ga2FQs8BUlU27MTF5Mbq28HJi68If3oemFu
          |ium9nNgJSPpNDup7UEcTyCTRs5RLo1AwTjAdBgNVHQ4EFgQUVACN213GZHtkJVem
          |/W3y2vOfE8owHwYDVR0jBBgwFoAUVACN213GZHtkJVem/W3y2vOfE8owDAYDVR0T
          |BAUwAwEB/zAKBggqhkjOPQQDAgNIADBFAiAvFveO6PaT/tTnEfR9wiQG3TnNukgr
          |Hj0WaRtgCBAuJwIhAOwmTuPS8+KqO3rkDzQYhwcCmD+AIpC2Jfx3TT5WdLwK""".stripMargin
    ).map(x => (x._1, Base64.getMimeDecoder.decode(x._2)))

    val entries = Algorithms.PEM.parse(cert.getBytes)
    (entries.size === expected.size) and entries.zip(expected).map { case (entry, (name, content)) =>
      (entry.name === name) and (entry.content === content)
    }.reduceLeft(_ and _)
  }

  private[this] def returnZeroLengthEntriesForDER = {
    val cert = Base64.getMimeDecoder.decode(
      """MHcCAQEEIP+G4bF24VHbhiFlSnI61OYvGfb4FRdIH12Ui6WMvvjAoAoGCCqGSM49
        |AwEHoUQDQgAEArZHyz6g/N3pj5n5RVtSouIGthULPAVJVNuzExeTG6tvByYuvCH9
        |6HphborpvZzYCUj6TQ7qe1BHE8gk0bOUSw==""".stripMargin)

    val entries = Algorithms.PEM.parse(cert)
    entries.size === 0
  }


  private[this] def ignoreUnmatchSeparator = {
    val cert =
      """-----BEGIN EC PRIVATE KEY-----
        |MHcCAQEEIP+G4bF24VHbhiFlSnI61OYvGfb4FRdIH12Ui6WMvvjAoAoGCCqGSM49
        |AwEHoUQDQgAEArZHyz6g/N3pj5n5RVtSouIGthULPAVJVNuzExeTG6tvByYuvCH9
        |6HphborpvZzYCUj6TQ7qe1BHE8gk0bOUSw==
        |-----END PRIVATE KEY-----
        |-----BEGIN CERTIFICATE-----
        |MIICOTCCAd+gAwIBAgIJAJBc9fHfC/h0MAoGCCqGSM49BAMCMHkxCzAJBgNVBAYT
        |AkpQMQ4wDAYDVQQIDAVUb2t5bzEPMA0GA1UEBwwGU3VtaWRhMREwDwYDVQQKDAhD
        |YXJpbGxvbjESMBAGA1UECwwJVW5pdCBUZXN0MSIwIAYDVQQDDBljYS5jYXJpbGxv
        |bi5hc3RlcmlzcXVlLmlvMB4XDTE5MDIxMTE4MjY0NloXDTIwMDIxMTE4MjY0Nlow
        |eTELMAkGA1UEBhMCSlAxDjAMBgNVBAgMBVRva3lvMQ8wDQYDVQQHDAZTdW1pZGEx
        |ETAPBgNVBAoMCENhcmlsbG9uMRIwEAYDVQQLDAlVbml0IFRlc3QxIjAgBgNVBAMM
        |GWNhLmNhcmlsbG9uLmFzdGVyaXNxdWUuaW8wWTATBgcqhkjOPQIBBggqhkjOPQMB
        |BwNCAAQCtkfLPqD83emPmflFW1Ki4ga2FQs8BUlU27MTF5Mbq28HJi68If3oemFu
        |ium9nNgJSPpNDup7UEcTyCTRs5RLo1AwTjAdBgNVHQ4EFgQUVACN213GZHtkJVem
        |/W3y2vOfE8owHwYDVR0jBBgwFoAUVACN213GZHtkJVem/W3y2vOfE8owDAYDVR0T
        |BAUwAwEB/zAKBggqhkjOPQQDAgNIADBFAiAvFveO6PaT/tTnEfR9wiQG3TnNukgr
        |Hj0WaRtgCBAuJwIhAOwmTuPS8+KqO3rkDzQYhwcCmD+AIpC2Jfx3TT5WdLwK
        |-----END CERTIFICATE-----
        |""".stripMargin
    val expected = Seq(
      "CERTIFICATE" ->
        """MIICOTCCAd+gAwIBAgIJAJBc9fHfC/h0MAoGCCqGSM49BAMCMHkxCzAJBgNVBAYT
          |AkpQMQ4wDAYDVQQIDAVUb2t5bzEPMA0GA1UEBwwGU3VtaWRhMREwDwYDVQQKDAhD
          |YXJpbGxvbjESMBAGA1UECwwJVW5pdCBUZXN0MSIwIAYDVQQDDBljYS5jYXJpbGxv
          |bi5hc3RlcmlzcXVlLmlvMB4XDTE5MDIxMTE4MjY0NloXDTIwMDIxMTE4MjY0Nlow
          |eTELMAkGA1UEBhMCSlAxDjAMBgNVBAgMBVRva3lvMQ8wDQYDVQQHDAZTdW1pZGEx
          |ETAPBgNVBAoMCENhcmlsbG9uMRIwEAYDVQQLDAlVbml0IFRlc3QxIjAgBgNVBAMM
          |GWNhLmNhcmlsbG9uLmFzdGVyaXNxdWUuaW8wWTATBgcqhkjOPQIBBggqhkjOPQMB
          |BwNCAAQCtkfLPqD83emPmflFW1Ki4ga2FQs8BUlU27MTF5Mbq28HJi68If3oemFu
          |ium9nNgJSPpNDup7UEcTyCTRs5RLo1AwTjAdBgNVHQ4EFgQUVACN213GZHtkJVem
          |/W3y2vOfE8owHwYDVR0jBBgwFoAUVACN213GZHtkJVem/W3y2vOfE8owDAYDVR0T
          |BAUwAwEB/zAKBggqhkjOPQQDAgNIADBFAiAvFveO6PaT/tTnEfR9wiQG3TnNukgr
          |Hj0WaRtgCBAuJwIhAOwmTuPS8+KqO3rkDzQYhwcCmD+AIpC2Jfx3TT5WdLwK""".stripMargin
    ).map(x => (x._1, Base64.getMimeDecoder.decode(x._2)))

    val entries = Algorithms.PEM.parse(cert.getBytes)
    (entries.size === expected.size) and entries.zip(expected).map { case (entry, (name, content)) =>
      (entry.name === name) and (entry.content === content)
    }.reduceLeft(_ and _)
  }
}
