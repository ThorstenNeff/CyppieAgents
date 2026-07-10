
---

## Nachtrag — Verortung (PO-Auflage)

Dieser vakuöse Test wird **nicht jetzt** repariert: der Resume-Pfad ist hinter **CYP-371** blockiert (der
Deadlock in `closeAndAwait`, dessen Abnahme in `docs/QA-SCENARIOS-CYP-371.md` liegt). Die Reparatur gehört an
**das Ticket, das den Resume-Pfad besitzt (CYP-330)**, und wird fällig, **wenn** CYP-330 wieder angefasst wird.

**Er darf nicht vergessen werden:** Ein Test, der grün bleibt, wenn man den Prozess ganz entfernt, ist keine
Regressionswache — er ist Dekoration. Solange er unrepariert steht, schützt der Name `restartWithLiveResume`
einen Pfad, den der Test nicht prüft.
