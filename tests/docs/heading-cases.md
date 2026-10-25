# Heading cases

Checked by ui/scripts/check-slugs.mjs and DocSectionsTest against "headingCases" in
slug-cases.json: which lines are section headings, and the anchor each one gets.

## First section

Text with `code` and a [link](#first-section).

```sh
# not a heading
## nor this
```

### Sub section ###

~~~
## still code
~~~

#### Deep heading

####### seven hashes is text

#no space is text

   ## Indented three spaces

    ## Indented four spaces is code

## First section

## Ends with hash #

## C# notes

```
## code again, with a longer fence below
````

### After the fence
