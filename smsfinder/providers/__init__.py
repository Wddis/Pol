from .asms import AsmsAi
from .fivesim import FiveSim
from .freephonenum import FreePhoneNum
from .onlinesim import OnlineSimFree
from .receivesms import ReceiveSmsCo
from .receivesmss import ReceiveSmss
from .smsactivate import SmsActivateCompat


def all_providers():
    return {p.name: p for p in (AsmsAi(), ReceiveSmsCo(), FreePhoneNum(), ReceiveSmss(), OnlineSimFree(),
                                FiveSim(), SmsActivateCompat())}
