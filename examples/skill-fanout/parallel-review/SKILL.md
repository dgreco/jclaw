---
name: Parallel review
description: Split an independent review into facets and give each one its own agent
when-to-use: a task splits into parts that can be read separately, and reading all of them yourself would fill the context with detail you will not need afterwards
---

Some tasks are one job. Others are several jobs that happen to have arrived in one sentence:
"review this module", "check these five services are configured the same way", "find every place
we still call the old API". This skill is for the second kind. Split the work into facets, give
each facet its own agent, and merge what comes back.

The reason to do this is not speed. It is that **you keep your context**: a subagent reads the
files, and you receive its conclusion. Four facets read in your own context is four times the
detail you will still be carrying when you write the answer.

## 1. Decide whether it splits

Delegate only facets that are genuinely independent. A facet qualifies when:

- it can be answered by reading things the other facets do not need to read, and
- nothing it finds changes what another facet should look for, and
- it does not write anything another facet reads.

If any of those fails, do the work yourself. Two agents editing the same file, or one agent
waiting on what another concludes, is not a fan-out — it is a sequence with extra steps, and it
will produce a merge you cannot trust.

Anything that must happen in a particular order stays with you.

## 2. Cut it into facets

Aim for **two to four**. Each one gets:

- a one-line name (`auth`, `error handling`, `configuration drift`);
- a boundary, stated as what to read — a directory, a file set, a question;
- the shape of the answer you want back.

Write the boundaries so that the facets do not overlap. Two agents finding the same thing is
budget spent twice and a merge you have to de-duplicate by hand.

## 3. Delegate one agent per facet

Call `builtin.spawn_subagent` once per facet. Three things about that call matter, and all three
bite silently if you get them wrong:

- **The child sees only the prompt.** It does not inherit this conversation, the original request,
  or anything you have read so far. A prompt that says "check the other half" means nothing to it.
  Write each prompt so that it stands alone: what to look at, what to look for, what to return.
- **Make every prompt distinct.** A child is identified by the parent thread and the task, so two
  facets sent with byte-identical prompts are one child, not two. Naming the facet in its own
  prompt is enough to keep them apart, and you should be doing that anyway.
- **The child can do nothing you could not do.** It runs under the same policy and the same
  approvals, so anything it touches that needs a human still stops and asks — separately, per
  call. A fan-out of four agents that each want to run a command is four questions, not one.

Ask for a fixed answer shape in every prompt, the same shape in each — a short list of findings,
one line each, with a file and a line number. Merging is mechanical when the pieces arrive in the
same format and archaeology when they do not.

Keep each facet's prompt to what that facet needs. It is the whole of the child's world, but it
is also budget: everything you put in it is read on every one of that child's turns.

## 4. Merge, yourself

Do not delegate the merge. It is the one part that needs all of the results, which is exactly
what no child has.

When the results come back:

- attribute each finding to the facet it came from, so the reader can tell one agent's confidence
  from another's;
- drop duplicates, and say when two facets independently found the same thing — that is a signal,
  not noise;
- **say what did not come back.** A facet whose agent failed, ran out of budget, or answered
  something other than what was asked is a hole in the review, and an answer that quietly omits it
  reads exactly like an answer that covered everything.

Then write one answer, in your own words, for the person who asked.
