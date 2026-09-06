# Single-issue: except E, e → except E as e
def safe_int(value):
    try:
        return int(value)
    except ValueError, e:
        return None
