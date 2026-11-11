from .fivesim import FiveSim
from .onlinesim import OnlineSimFree
from .receivesms import ReceiveSmsCo
from .smsactivate import SmsActivateCompat


def all_providers():
    return {p.name: p for p in (ReceiveSmsCo(), OnlineSimFree(), FiveSim(), SmsActivateCompat())}
