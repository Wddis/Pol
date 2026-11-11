from .fivesim import FiveSim
from .freephonenum import FreePhoneNum
from .onlinesim import OnlineSimFree
from .receivesms import ReceiveSmsCo
from .smsactivate import SmsActivateCompat


def all_providers():
    return {p.name: p for p in (FreePhoneNum(), ReceiveSmsCo(), OnlineSimFree(), FiveSim(), SmsActivateCompat())}
