from clip_core import timeline
assert timeline([[6042,6197.2],[6213,6526.5]],10,6213) == 165.2
assert timeline([[6042,6197.2],[6213,6526.5]],10,6200) is None
