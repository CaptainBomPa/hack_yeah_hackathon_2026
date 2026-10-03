# Eksperyment: kNN na embeddingach (priorytet 2 backlogu)

Data: 2026-10-03. Skrypt: `python -m scripts.knn_experiment`. Korpus dodatkowy: `evaluation/index_corpus/` (70 trudnych negatywów, 38 ataków, pisane ręcznie).

**Hipoteza:** kNN z indeksem ataków i niewinnych (z trudnymi negatywami) obniży fałszywe alarmy `protectai` na trudnych negatywach.
**Wynik: hipoteza NIE potwierdzona.** kNN mocno poprawia ranking na `neuralchemy`, ale ta poprawa pochodzi głównie ze szablonowości tego zbioru.
Na naszych danych (inny rozkład) nie daje wiarygodnej poprawy, a odsetek fałszywych alarmów na trudnych negatywach się nie zmienia.

## Protokół (bez wycieku danych)

- Indeks: `neuralchemy/train` (4391) + opcjonalnie korpus ręczny (108). Embeddingi: `MiniLM` i `bge-small`, kNN brute-force w numpy.
- Próg i stacker (regresja logistyczna na `[margines protectai, wynik kNN]`) dopasowane na `neuralchemy` **validation** (941).
- Ocena: **test** (942) oraz **nasze ręczne** (98 przypadków dla punktów P1/P2/P5, inny rozkład). Próg z validation stosowany bez zmian.
- Ocenione 24 konfiguracje: 2 modele embeddingów × 2 indeksy × k ∈ {5, 10, 20} × 2 wyniki (`diff`, `vote`).

## Wyniki

Baza: `protectai` sam, próg z validation dla FPR 1%.

| | AUROC val / test / nasze | test: recall / FPR / FPR trudnych | nasze: recall / FPR / FPR trudnych |
|---|---|---|---|
| `protectai` sam | 0,971 / 0,976 / 0,930 | 67,9% / 0,3% / 0% | 60,9% / 9,6% / 17,2% |
| kNN sam (MiniLM, train+korpus, k=5, diff) | 0,994 / 0,994 / **0,885** | | |
| stack `protectai` + kNN (ta konfiguracja) | 0,995 / 0,995 / 0,942 | 91% / 0,3% / 0% | 89% / 9,6% / 17% |
| stack, ta sama konfiguracja bez korpusu | 0,995 / 0,995 / 0,913 | 91% / 0,5% / 11% | 91% / 26,9% / 48% |

Uwaga: wiersz „stack, najlepsza konfiguracja" wybrałam, patrząc na wynik na naszych danych. Walidacja nie rozróżnia konfiguracji
(AUROC 0,995 wszędzie), więc **0,942 jest optymistyczny** (selekcja na zbiorze oceny). Wszystkie 24 wiersze są w wyniku skryptu.

## Dlaczego kNN wygląda dobrze na `neuralchemy`

`neuralchemy` jest szablonowy. 24% jego zapytań ma w indeksie niemal duplikat (najbliższe podobieństwo ≥ 0,90), a mediana to 0,80.
Nasze ręczne przypadki są inne: mediana 0,53, tylko 6% ≥ 0,90, a 81% < 0,70.

AUROC na `test`, w zależności od podobieństwa do indeksu:

| Najbliższe podobieństwo | n (ataki / negatywy) | `protectai` | kNN sam | stack |
|---|---|---|---|---|
| ≥ 0,90 | 129 / 85 | 0,996 | 0,999 | 1,000 |
| 0,70–0,90 | 251 / 224 | 0,980 | 0,998 | 0,998 |
| < 0,70 (nowe) | 172 / 81 | 0,954 | 0,966 | 0,970 |

Nasze ręczne, podzbiór „niepodobne do indeksu" (< 0,70, n = 31 / 48): `protectai` **0,928**, kNN sam **0,838**, stack **0,933**.

## Wnioski

1. **Na danych podobnych do indeksu kNN pomaga bardzo** (recall przy FPR ≈ 0,3% rośnie z 68% do 91% na `test`). Na nowych tekstach zysk jest mały
   (+0,016 AUROC na `neuralchemy` <0,70, +0,005 na naszych). To w dużej mierze efekt szablonowości zbioru, a nie lepsze rozpoznawanie nowych ataków.
2. **kNN sam jest gorszy od `protectai` na naszych danych** (0,84–0,89 vs 0,93).
3. **Trudne negatywy nie zostały rozwiązane.** Przy progu przeniesionym z validation FPR na naszych trudnych negatywach jest równy bazie (17,2%).
4. **Przeniesienie progu jest niestabilne:** dla MiniLM z korpusem FPR na naszych danych wychodzi od 1,9% do 21%, a recall od 28% do 93%, zależnie od `k` i wyniku.
   Ranking (AUROC) jest stabilny, próg nie.
5. **Najsilniejszy zaobserwowany czynnik to dane, a nie metoda:** dodanie 70 trudnych negatywów do indeksu obniżyło FPR na naszych danych z 26,9% do 9,6%
   (AUROC stacka 0,913 → 0,942). **Uwaga: to potwierdzenie obarczone ryzykiem**, bo ten sam autor napisał korpus i nasz zbiór oceny i obie listy mają
   podobną taksonomię (słowa kluczowe, persony, dokumentacja). Średnie najbliższe podobieństwo naszych trudnych negatywów do korpusu (0,48) nie jest większe
   niż do `train` (0,50), więc nie wygląda to na kopiowanie zdań, ale zakresu tematów to nie wyklucza. Niezależnego zbioru trudnych negatywów nie mamy.
6. `neuralchemy` ma tylko 9 trudnych negatywów w `test` (11 w validation), więc ocena na nich jest bardzo niepewna.

## Decyzja i dalsze kroki

- **Nie dodajemy stackera `protectai` + kNN do produkcji.** Zysk poza rozkładem `neuralchemy` jest w granicach szumu, a próg się nie przenosi.
- Jeśli kNN będzie potrzebny, to **jako osobny sygnał do innego celu**: wyjaśnialność („najbliższy znany atak: X") i dodawanie przykładów atakujących
  na żywo (wymóg demo: jury zmienia konfigurację i dokłada ataki). Oceniałabym go wtedy jako funkcję, a nie jako sposób na obniżenie fałszywych alarmów.
- **Wniosek praktyczny: więcej i bardziej różnorodnych trudnych negatywów** (najlepiej napisanych przez kogoś innego, żeby obniżyć ryzyko z pkt 5) to najtańszy
  zaobserwowany kierunek. Kolejny kandydat: douczanie klasyfikatora z trudnymi negatywami.
- Do rozważenia: `bge` wypadł zwykle gorzej niż MiniLM przy równych warunkach (np. nasze AUROC 0,883 vs 0,913 bez korpusu), więc przy dalszych pracach domyślnie MiniLM.
