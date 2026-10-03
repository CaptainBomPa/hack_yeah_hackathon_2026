"""Mały słownik częstych angielskich słów. Służy tylko do oceny, czy odkodowany (rot13, odwrócony, leet) tekst
wygląda jak angielski. To nie jest lista ataków."""

COMMON = frozenset(
    """
a about above access account across act actually admin after again against all also always am an and any are as ask
assistant at available back bank be because been before below between bypass both but by call can card change chat code
command complete confidential content contents context could credit data delete developer did disabled disregard do
does done down during each earlier else email entire everything file files filters find first follow for forget forward
from full get give grant had has have help her here hidden his how if ignore in initial instruction instructions
internal into is it its just key keys know let list login look make many may me mention message might mode money more
most must my need new no not now number of on once only or original other our out output over own password payment
permission permissions pretend previous print prior private prompt question read remove repeat reset restrictions
reveal role rules run safety same say secret see send shall should show so some something system take tell text than
that the their them then there these they this those through to token too under unrestricted up use user verbatim very
want was we were what when where which who why will with within without work would write yes you your
""".split()
)
