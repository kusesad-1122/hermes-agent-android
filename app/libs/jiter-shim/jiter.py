"""jiter shim — pure Python replacement for the Rust jiter package."""
import json

def from_json(data, **kwargs):
    if isinstance(data, bytes):
        data = data.decode('utf-8')
    return json.loads(data)
