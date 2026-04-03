from fuzzer.harness_client import HarnessClient
import json


def test_format_request():
    client = HarnessClient.__new__(HarnessClient)
    req = client._format_request("test-1", "###Pure\nClass a::B {}")
    parsed = json.loads(req)
    assert parsed["id"] == "test-1"
    assert parsed["code"] == "###Pure\nClass a::B {}"


def test_parse_response():
    client = HarnessClient.__new__(HarnessClient)
    resp = '{"id":"test-1","result":"COMPILE_OK","time_ms":42}'
    parsed = client._parse_response(resp)
    assert parsed["id"] == "test-1"
    assert parsed["result"] == "COMPILE_OK"
    assert parsed["time_ms"] == 42


def test_is_crash():
    client = HarnessClient.__new__(HarnessClient)
    assert client._is_crash({"result": "PARSE_CRASH"})
    assert client._is_crash({"result": "COMPILE_CRASH"})
    assert not client._is_crash({"result": "COMPILE_OK"})
    assert not client._is_crash({"result": "PARSE_ERROR"})
    assert not client._is_crash({"result": "COMPILE_ERROR"})
