"""Phase 5 tasks: 28 tasks in 6 families over the memory store of phase5_store.py. Each task is written against the recording reference
store, so the task IS its gold: the sessions' texts, the operations a correct agent performs (the gold final state), and the answers,
all computed from the seed. Nothing here is shown to a model except the session texts and the answer-format sentence.
"""
import phase5_store as S

FAMILIES = ["remember", "recall", "correct", "forget", "consolidate", "reason"]


def memtext(R, i):
    return '"' + R.mem(i)["content"] + '"'


def t_R1(R):
    R.session('Store a memory with content "Carol booked the Osaka flights", kind "event", importance 4, created 2026-07-01, filed under the '
              'topic travel and mentioning Carol.')
    m = R.add("Memory", content="Carol booked the Osaka flights", kind="event", importance=4, created="2026-07-01")
    R.link("ABOUT", m, R.tid("travel"))
    R.link("MENTIONS", m, R.pid("Carol"))


def t_R2(R):
    R.session('Store two new memories, in this order. First: content "Lena prefers window seats", kind "preference", importance 3, created '
              '2026-07-02, filed under travel, mentioning Lena. Second: content "Renew the gym membership", kind "plan", importance 5, '
              'created 2026-07-03, filed under health, mentioning nobody.')
    a = R.add("Memory", content="Lena prefers window seats", kind="preference", importance=3, created="2026-07-02")
    R.link("ABOUT", a, R.tid("travel"))
    R.link("MENTIONS", a, R.pid("Lena"))
    b = R.add("Memory", content="Renew the gym membership", kind="plan", importance=5, created="2026-07-03")
    R.link("ABOUT", b, R.tid("health"))


def t_R3(R):
    R.session('Store a memory: content "Bob and Eve discussed the Lisbon conference", kind "event", importance 2, created 2026-07-04, filed '
              'under work, mentioning Bob and Eve.')
    m = R.add("Memory", content="Bob and Eve discussed the Lisbon conference", kind="event", importance=2, created="2026-07-04")
    R.link("ABOUT", m, R.tid("work"))
    R.link("MENTIONS", m, R.pid("Bob"))
    R.link("MENTIONS", m, R.pid("Eve"))
    R.session('You have no memory of earlier sessions. Some time ago a memory about Bob, Eve and the Lisbon conference was stored. Raise its '
              'importance to 4 and also file it under travel (keep its other links).')
    R.set("Memory", m, importance=4)
    R.link("ABOUT", m, R.tid("travel"))


def t_R4(R):
    R.session('Add a new person to the memory with name "Mira" and role "friend". Then store a memory with content "Mira lives next to the old harbour", kind '
              '"note", importance 2, created 2026-07-05, filed under home and mentioning Mira.')
    p = R.add("Person", name="Mira", role="friend")
    m = R.add("Memory", content="Mira lives next to the old harbour", kind="note", importance=2, created="2026-07-05")
    R.link("ABOUT", m, R.tid("home"))
    R.link("MENTIONS", m, p)


def t_R5(R):
    R.session('Add a new topic called "gardening".')
    g = R.add("Topic", name="gardening")
    R.session('You have no memory of earlier sessions. Store two memories, in this order, both filed under the topic gardening and mentioning '
              'nobody: "Plant the tomatoes in May" (kind "plan", importance 3, created 2026-07-06) and "Buy compost in April" (kind "plan", '
              'importance 2, created 2026-07-07).')
    for c, imp, d in (("Plant the tomatoes in May", 3, "2026-07-06"), ("Buy compost in April", 2, "2026-07-07")):
        m = R.add("Memory", content=c, kind="plan", importance=imp, created=d)
        R.link("ABOUT", m, g)
    R.session('You have no memory of earlier sessions. How many memories are filed under the topic gardening?', ("int", 2))


def t_Q1(R):
    R.session("What are the three most recent memories (by created date) filed under the topic travel? Give their ids, newest first.")
    ms = sorted(R.mems(topic="travel"), key=lambda i: R.mem(i)["created"], reverse=True)[:3]
    R.answer(("list", ms))


def t_Q2(R):
    R.session("Which memories that mention Bob have importance 4 or 5? Give their ids.")
    ms = [i for i in R.mems(person="Bob") if R.mem(i)["importance"] >= 4]
    assert ms
    R.answer(("set", ms))


def t_Q3(R):
    R.session("Under which topics are the memories that mention Alice filed? Give the topic names.")
    ts = {R.T["Topic"][t]["name"] for m in R.mems(person="Alice") for t in R.topics_of(m)}
    assert len(ts) >= 2
    R.answer(("set", sorted(ts)))


def t_Q4(R):
    R.session("In which city does Dana live now? Use the city fact that has no end date.")
    f = R.facts_of("Dana", "city")
    R.answer(("text", R.T["Fact"][f[-1]]["detail"]))
    R.session("You have no memory of earlier sessions. Which city did Dana live in just before her current city? Use the facts and their dates.")
    R.answer(("text", R.T["Fact"][f[-2]]["detail"]))


def t_Q5(R):
    R.session("What is the average importance of the memories filed under the topic food? Give the number with two decimals.")
    ms = R.mems(topic="food")
    R.answer(("float", round(sum(R.mem(i)["importance"] for i in ms) / len(ms), 2)))


def t_C1(R):
    m = next(i for i in R.mems(topic="music") if R.mem(i)["importance"] < 5)
    R.session(f"The memory {memtext(R, m)} is more important than recorded. Set its importance to 5.")
    R.set("Memory", m, importance=5)


def t_C2(R):
    m = next(i for i in R.mems(topic="work") if R.mem(i)["kind"] == "plan")
    old = R.mem(m)["content"]
    R.session(f'Append " by Friday" to the content of the memory "{old}" (the content then ends with "by Friday"). Change nothing else.')
    R.set("Memory", m, content=old + " by Friday")


def t_C3(R):
    f = R.facts_of("Dana", "city")[-1]
    old = R.T["Fact"][f]["detail"]
    R.session(f"Dana moved from {old} to Groningen on 2026-08-01. Record it: end her current city fact on 2026-07-31, add a new fact "
              "with attribute \"city\" and detail \"Groningen\", valid from 2026-08-01 with valid_to null (no end date), describing Dana, and record "
              "that the new fact supersedes the old one.")
    R.set("Fact", f, valid_to="2026-07-31")
    n = R.add("Fact", attribute="city", detail="Groningen", valid_from="2026-08-01", valid_to=None)
    R.link("DESCRIBES", n, R.pid("Dana"))
    R.link("SUPERSEDES", n, f)


def t_C4(R):
    f = R.facts_of("Eve", "employer")[-1]
    R.session('Eve\'s current employer fact is wrong: it must say "Initech", not "Hooli". Correct that fact in place: do not add a fact and '
              "do not change its dates.")
    R.set("Fact", f, detail="Initech")
    R.session("You have no memory of earlier sessions. Which employer does the memory hold for Eve now (the employer fact with no end date)?",
              ("text", "Initech"))


def t_C5(R):
    m = next(i for i in R.mems(topic="home") if len(R.topics_of(i)) == 1)
    R.session(f"The memory {memtext(R, m)} is filed under home by mistake. File it under books instead of home (keep its other links).")
    R.unlink("ABOUT", m, R.tid("home"))
    R.link("ABOUT", m, R.tid("books"))


def t_F1(R):
    m = next(i for i in R.mems(topic="food") if len(R.topics_of(i)) == 1 and R.mentions_of(i))
    R.session(f"Forget the memory {memtext(R, m)}: remove it and its links, and nothing else.")
    R.delete("Memory", m)


def t_F2(R):
    ms = [i for i in R.mems(topic="music") if R.mem(i)["created"] < "2026-03-01"]
    assert len(ms) >= 2
    R.session("Forget every memory filed under the topic music that was created before 2026-03-01. Keep the topic, the persons and every other memory.")
    for i in ms:
        R.delete("Memory", i)


def t_F3(R):
    R.session("Forget Frank completely: delete the person Frank and every fact that describes him. Keep the memories that mention him "
              "(they only lose the link to him). Remove every link of the deleted records.")
    p = R.pid("Frank")
    for f in sorted(f for f, q in R.E["DESCRIBES"] if q == p):
        R.delete("Fact", f)
    R.delete("Person", p)


def t_F4(R):
    R.session("How many memories mention Gina?")
    R.answer(("int", len(R.mems(person="Gina"))))
    g = R.pid("Gina")
    only = [i for i in R.mems(person="Gina") if R.mentions_of(i) == [g]]
    assert only
    R.session("You have no memory of earlier sessions. Forget every memory that mentions Gina and no other person (a memory that mentions "
              "Gina and somebody else stays). Remove the links of the forgotten memories.")
    for i in only:
        R.delete("Memory", i)


def t_F5(R):
    h = R.pid("Henry")
    m = next(i for i in R.mems(person="Henry") if len(R.mentions_of(i)) == 2)
    R.session(f"The memory {memtext(R, m)} mentions Henry by mistake. Remove only the link between this memory and Henry. Keep the memory and its other links.")
    R.unlink("MENTIONS", m, h)


def t_K1(R):
    seen, dups = {}, []
    for i in sorted(R.T["Memory"], key=lambda i: (R.mem(i)["created"], i)):
        c = R.mem(i)["content"]
        if c in seen:
            dups.append(i)
        else:
            seen[c] = i
    assert dups
    R.session("Some memories were stored twice (identical content). For every content that occurs more than once, keep the copy with the earliest "
              "created date and forget the later copies together with their links.")
    for i in dups:
        R.delete("Memory", i)


def t_K2(R):
    months = [("2026-01", "2026-01-31"), ("2026-02", "2026-02-28"), ("2026-03", "2026-03-31")]
    counts = [sum(1 for r in R.T["Memory"].values() if r["created"].startswith(m)) for m, _ in months]
    R.session("Count the memories created in each of January, February and March 2026 (count before you add anything). Then store three new "
              'memories, one per month in that order: content "Summary 2026-01: N memories" (and likewise 2026-02, 2026-03) with the real count '
              'N, kind "summary", importance 3, created on the last day of that month. They have no links.')
    for (m, last), n in zip(months, counts):
        R.add("Memory", content=f"Summary {m}: {n} memories", kind="summary", importance=3, created=last)


def t_K3(R):
    job, work = R.tid("job"), R.tid("work")
    R.session('The topics "job" and "work" mean the same. Merge "job" into "work": every memory filed under "job" must be filed under "work" '
              'instead (once, never twice), and then the topic "job" must be removed.')
    for m in R.mems(topic="job"):
        if (m, work) not in R.E["ABOUT"]:
            R.link("ABOUT", m, work)
    R.delete("Topic", job)


def t_K4(R):
    ms = [i for i in R.mems() if R.mem(i)["kind"] == "note" and R.mem(i)["importance"] == 1]
    R.session('How many memories have kind "note" and importance 1?', ("int", len(ms)))
    old = [i for i in ms if R.mem(i)["created"] < "2026-04-01"]
    assert old and len(old) < len(ms)
    R.session('You have no memory of earlier sessions. Forget the memories of kind "note" with importance 1 that were created before 2026-04-01. '
              "Remove their links.")
    for i in old:
        R.delete("Memory", i)


def t_H1(R):
    ps = {R.T["Person"][p]["name"] for m in R.mems(topic="travel") if R.mem(m)["created"] > "2026-04-01" for p in R.mentions_of(m)}
    assert len(ps) >= 2
    R.session("Which persons are mentioned in memories filed under the topic travel that were created after 2026-04-01? Give their names.")
    R.answer(("set", sorted(ps)))


def t_H2(R):
    names = [n for n, _ in S.PERSONS]
    pair = None
    for i, a in enumerate(names):
        for b in names[i + 1:]:
            ta = {t for m in R.mems(person=a) for t in R.topics_of(m)}
            tb = {t for m in R.mems(person=b) for t in R.topics_of(m)}
            if 2 <= len(ta & tb) <= 4 and pair is None:
                pair = (a, b, ta & tb)
    a, b, ts = pair
    R.session(f"Which topics have at least one memory that mentions {a} and at least one memory that mentions {b}? Give the topic names.")
    R.answer(("set", sorted(R.T["Topic"][t]["name"] for t in ts)))


def t_H3(R):
    R.session("Follow the chain of superseded facts: what was the earliest city recorded for Dana (the oldest fact in her chain of city facts)?")
    f = R.facts_of("Dana", "city")
    R.answer(("text", R.T["Fact"][f[0]]["detail"]))


def t_H4(R):
    iv = R.pid("Ivan")
    ms = [m for m in R.mems(topic="travel") if "Lisbon" in R.mem(m)["content"] and (m, iv) not in R.E["MENTIONS"]]
    assert ms
    R.session('Link every memory filed under the topic travel whose content contains the word "Lisbon" to Ivan (a mentions link), unless it '
              "already mentions Ivan. Change nothing else.")
    for m in ms:
        R.link("MENTIONS", m, iv)


TASKS = [("R1", "remember", t_R1), ("R2", "remember", t_R2), ("R3", "remember", t_R3), ("R4", "remember", t_R4), ("R5", "remember", t_R5),
         ("Q1", "recall", t_Q1), ("Q2", "recall", t_Q2), ("Q3", "recall", t_Q3), ("Q4", "recall", t_Q4), ("Q5", "recall", t_Q5),
         ("C1", "correct", t_C1), ("C2", "correct", t_C2), ("C3", "correct", t_C3), ("C4", "correct", t_C4), ("C5", "correct", t_C5),
         ("F1", "forget", t_F1), ("F2", "forget", t_F2), ("F3", "forget", t_F3), ("F4", "forget", t_F4), ("F5", "forget", t_F5),
         ("K1", "consolidate", t_K1), ("K2", "consolidate", t_K2), ("K3", "consolidate", t_K3), ("K4", "consolidate", t_K4),
         ("H1", "reason", t_H1), ("H2", "reason", t_H2), ("H3", "reason", t_H3), ("H4", "reason", t_H4)]


def build_tasks():
    """[{id, family, sessions: [{text, answer, ops}], final_state, final_lenient, read_only}] from the seed, each on a fresh copy."""
    seed = S.build_seed()
    out = []
    for tid, fam, fn in TASKS:
        R = seed.clone_data()
        fn(R)
        for s in R.sessions:
            if s["answer"] is not None:
                s["text"] += f" Give the answer as {S.answer_format(s['answer'])}; finish your final message with a line ANSWER: followed by the answer."
        out.append({"id": tid, "family": fam, "sessions": R.sessions, "final_state": R.state(), "final_lenient": R.lenient(),
                    "read_only": R.state() == seed.state()})
    return seed, out


def scan_ambiguities(tasks):
    """Every string a gold operation stores or sets, checked against the session text that asks for it. Findings: `unquoted` (the value
    occurs in the text but never inside double quotes), `punct_after` (an unquoted value is followed by . , ; ! ?), `not_in_text` (a value the
    agent has to compute, listed for the record), `space` (leading or trailing whitespace in a gold value), `date_format` (a date column that
    is not YYYY-MM-DD). Prose columns are compared after normalisation, everything else exactly: a finding on an exact column is a defect of
    the task text, never fixed in the gold."""
    import re
    out, n = [], 0
    for t in tasks:
        text = " ".join(s["text"] for s in t["sessions"])
        for sess in t["sessions"]:
            for op in sess["ops"]:
                if op[0] == "add":
                    vals = {c: v for c, v in op[2].items() if isinstance(v, str)}
                elif op[0] == "set":
                    vals = {c: v for c, v in op[3].items() if isinstance(v, str)}
                else:
                    continue
                for c, v in vals.items():
                    n += 1
                    if v != v.strip() and not (t["id"] == "C2" and c == "content"):
                        out.append((t["id"], c, v, "space"))
                    if c in ("created", "valid_from", "valid_to") and not re.fullmatch(r"\d{4}-\d{2}-\d{2}", v):
                        out.append((t["id"], c, v, "date_format"))
                    if f'"{v}"' in text or (c in ("created", "valid_from", "valid_to") and v in text):
                        continue
                    if v in text:
                        k = text.index(v) + len(v)
                        out.append((t["id"], c, v, "punct_after" if k < len(text) and text[k] in ".,;!?" else "unquoted"))
                    else:
                        out.append((t["id"], c, v, "not_in_text"))
    return n, out


if __name__ == "__main__":
    seed, ts = build_tasks()
    import collections
    print(len(ts), dict(collections.Counter(t["family"] for t in ts)), "sessions", sum(len(t["sessions"]) for t in ts))
    for t in ts:
        print(t["id"], len(t["sessions"]), "ops", [len(s["ops"]) for s in t["sessions"]], "ans", [s["answer"] for s in t["sessions"] if s["answer"]], "ro", t["read_only"])
