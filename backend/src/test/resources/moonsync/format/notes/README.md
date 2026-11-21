# Moon+ `.an` note-slot samples (Moon+ 10.8, real phone)

Each `stepNN-*.an` is the `.an` Moon+ uploaded to its Drive sync folder after that step of one manual session,
downloaded unchanged (zlib). Book A is an EPUB of "The Simple Path to Wealth"; book B (step 10) is
"L'atlante delle nuvole". Header `total_length` is each book file's size.

| Step | What was done in Moon+ | What the file shows |
|---|---|---|
| 01 | highlight, no note | empty bookmark and note slots |
| 02 | highlight with a one-line note | `Single line note sample ` (keyboard's trailing space kept) |
| 03 | highlight with a two-line note | `Multi line note<BR>Sample text ` |
| 04 | note with accents, quotes, `#$*:`, emoji | stored verbatim |
| 05 | edit the step 02 note | same record, new note `Single line note edited ` |
| 06 | bookmark "Bookmark test" | name in the bookmark slot, empty note and text; file name as title |
| 07 | highlight across three paragraphs, with a note | paragraph breaks as `<BR>` in the text, note separate |
| 08 | squiggly orange highlight with a note | `bak` `1`, colour `-28160` |
| 09 | delete the step 03 highlight (note popup → Delete) | record gone, **no** `*DELETED*` record (book reopened before download) |
| 10 | book B: one highlight | |
| 11 | force-stop, reopen book A, one more highlight | |
| 12 | trash-bin delete of the step 05 highlight, download without reopening | one `*DELETED*` record keeping the note |
| 13 | note-popup delete of the step 08 highlight, download without reopening | only its own `*DELETED*` record (step 12's cleared by the reopen) |

`notes-table-after-step11.csv` is Moon+'s `notes` table for both books, exported from the app's `.mrpro` backup
after step 11: the ground truth for each column's decoded value (real newlines where the `.an` has `<BR>`).
