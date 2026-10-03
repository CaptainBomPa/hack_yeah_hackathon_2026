# Koncepcja rozwiązania — element wyróżniający

> Uzupełnienie do [analiza-wyzwania.md](analiza-wyzwania.md). Cel: rozwiązanie kreatywne, z elementem wyróżniającym, który jury zobaczy i zapamięta.

## Kierunek: matchmaking, który da się zobaczyć i usłyszeć

Większość zespołów zrobi to samo: formularz, wyszukiwarkę, listę kart innowacji i chatbota w rogu ekranu. Jury obejrzy kilkanaście takich projektów. Odwracamy kolejność: punktem wyjścia nie jest formularz, tylko rozmowa. Wynikiem nie jest lista, tylko mapa Małopolski, na której widać połączenia.

Pięć pomysłów. Każdy działa osobno, ale najlepiej sprawdzą się razem, jako jedna historia.

## 1. Głosowe zgłaszanie problemu

Najmocniejszy kandydat na element wyróżniający. Mieszkanka naciska duży przycisk „Opowiedz” i mówi własnymi słowami, np. „Sąsiad od miesięcy nie wychodzi z domu, nie ma rodziny, martwię się”. AI:

- zamienia mowę na tekst i zadaje jedno lub dwa dopytujące pytania (głosem i tekstem),
- samo wypełnia za nią „fiszkę problemu”: obszar (samotność, seniorzy), gminę, grupę docelową,
- pokazuje fiszkę do akceptacji dużymi, czytelnymi elementami.

Dlaczego to działa:

- Rozwiązuje problem wpisany w samo wyzwanie, czyli wykluczenie cyfrowe i seniorów. To nie jest dodatek, tylko odpowiedź na treść zadania.
- Wprost trafia w kryterium „łatwość zgłoszenia problemu”, a do tego w 20% za dostępność.
- Można to pokazać na żywo. Juror sam mówi do mikrofonu, a system od razu odpowiada.

Technicznie: Whisper albo Web Speech API (`pl-PL`) + LLM do strukturyzacji. Tryb tekstowy musi zawsze działać jako alternatywa (wymóg WCAG, a w hali bywa głośno).

## 2. Żywa Mapa Małopolski

Główny ekran platformy i drugi element wyróżniający. Na ciemnej mapie województwa:

- zgłoszone problemy to pulsujące punkty w gminach, kolor oznacza obszar (zdrowie psychiczne, samotność, starzenie się),
- innowacje z Biblioteki to stałe znaczniki w miejscach, gdzie je wdrożono,
- po zgłoszeniu problemu animowane łuki łączą gminę zgłaszającą z miejscami, gdzie podobny problem już rozwiązano.

Przykładowy komunikat: „W gminie podobnej do Twojej (podobna liczba mieszkańców i odsetek seniorów) rozwiązano to tak. Lider innowacji jest 40 km od Ciebie.” Matchmaking pokazany wizualnie — jury dosłownie widzi, jak system łączy ludzi i rozwiązania.

Dodatkowa korzyść: ten sam widok z filtrem „trendy” staje się panelem administratora. Skupiska punktów pokazują, gdzie rośnie dany problem (wymóg Zasobnika wiedzy). Jeden komponent obsługuje dwa moduły.

Technicznie: MapLibre lub deck.gl (ArcLayer do łuków), GeoJSON gmin Małopolski (dane publiczne), dane syntetyczne.

## 3. „Bliźniak gminy” — matchmaking ludzi, nie tylko dokumentów

Rozszerzenie matchmakingu. System podpowiada nie tylko innowacje, ale też gminę-bliźniaka: JST o podobnym profilu demograficznym, która miała ten sam problem i już go rozwiązała. Obok przycisk „Poproś o kontakt z liderem”.

Trafia w budowanie partnerstw międzysektorowych i w problem zmiany struktury osadniczej z treści wyzwania (depopulacja vs przyrost wokół Krakowa). Wygląda pomysłowo, a w kodzie to podobieństwo wektorów cech gmin.

## 4. Middleman jako „Karta wdrożenia” dla JST

Urzędnik wybiera innowację i podaje parametry gminy: budżet, liczbę mieszkańców, dostępne zasoby. AI generuje gotową jednostronicową kartę usługi do pobrania jako PDF: zakres usługi dopasowany do gminy, szacowany koszt, harmonogram, potrzebni partnerzy z okolicy, ryzyka.

Na demo widać efekt: z innowacji powstaje dokument, który urzędnik może jutro położyć na biurku wójta. Bezpośrednia odpowiedź na hasło „eliminacji biurokracji” z treści wyzwania.

## 5. Kreator z wizualizacją pomysłu

Asystent prowadzi rozmowę według Canwy Innowacji Społecznych i wypełnia ją na żywo na ekranie. Na koniec generuje obraz koncepcyjny pomysłu, np. „ławka z przyciskiem przywołania wolontariusza”. Wizualizację treść wyzwania wymienia wprost jako „mile widzianą”.

Ryzyko: generowanie obrazu bywa wolne i nieprzewidywalne. Na demo mieć gotowy, sprawdzony przykład.

## Rekomendacja: jedna historia zamiast pięciu funkcji

Koncepcja robocza: **„Splot – Małopolska sieć dobrych rozwiązań”** (nazwa do dyskusji; nawiązuje do łuków łączących punkty na mapie).

Scenariusz demo i filmu (3 minuty):

1. Pani Halina, 72 lata, z małej gminy, mówi do platformy o samotnym sąsiedzie. (Głos)
2. Mapa się rozświetla: łuki do trzech innowacji i do gminy-bliźniaka. (Mapa + Bliźniak)
3. Urzędniczka z tej gminy otwiera innowację i generuje kartę wdrożenia z kosztami. (Middleman)
4. Koordynator ROPS widzi w panelu nowe zgłoszenie i skupisko „samotność” na mapie trendów, odpisuje autorce. (Admin + komunikacja)
5. Lokalny aktywista rozwija własny pomysł w Kreatorze i dostaje wizualizację. (Kreator)

Jedna postać przechodzi przez całą ścieżkę — jury widzi 5–6 modułów połączonych w spójny produkt, a nie listę funkcji.

## Przełożenie na punkty

| Element | Kryterium |
|---|---|
| Głosowe zgłaszanie | Dostępność 20%, łatwość zgłoszenia, pomysłowość 10% |
| Żywa Mapa | Pomysłowość i UI 10%, matchmaking 10%, Zasobnik wiedzy i trendy +5% |
| Bliźniak gminy | Trafność dopasowania, partnerstwa |
| Karta wdrożenia | Middleman +5%, potencjał wdrożeniowy 20% |
| Kreator z wizualizacją | Kreator +5%, efekt „wow” |
| Historia Pani Haliny | Jakość materiałów 10% |

## Priorytet przy braku czasu

1. Głos + matchmaking
2. Żywa Mapa
3. Karta wdrożenia
4. Bliźniak gminy
5. Wizualizacja w Kreatorze

Pierwsze dwa elementy wystarczą, żeby projekt się wyróżniał. Pozostałe dokładają punkty za moduły.
