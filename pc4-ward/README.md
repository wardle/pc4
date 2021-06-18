# pc4-ward 

A demonstrator application for making referrals.

It has a deliberately *limited* scope, designed to support

* user login
* patient lookup / selection from ward lists
* completion / auto-completion of clinical details
* sending the referral

in order to:

* suit a workflow on a hospital ward.
* test a new architecture for PatientCare v4.

For its main operations, it connects to `pc4-server`.

A core principle is that user-facing applications should be smart in
user interactions but dumb in terms of business logic; the latter are
delegated to the backend server.

Fetching data from the backend uses a graph API. Changes are made using
events, sent to the server.


# Future plans

This is a small modular application. It is designed to show how a 
suite of potentially independently developed modules can interact with
the same server-side components to solve problems.

### An lightweight e-observations application.

This has a deliberately *limited* scope, designed to support 

* user login
* patient lookup / selection from ward lists
* review of results (tabulation and charts)
* entry of new e-observations

in order to:

* suit a workflow on a hospital ward.
* test a new architecture for PatientCare v4. 
  
For its main operations, it connects to `pc4-server`. 

Although initially components such as the SVG NEWS2 chart will be built into 
this application, they will be put into a shared library for re-use across
multiple user-facing applications.


### Environment Setup

1. Install [JDK 8 or later](https://openjdk.java.net/install/) (Java Development Kit)
2. Install [Clojure CLI tools.](https://clojure.org/guides/getting_started) (Clojure/ClojureScript project task &
dependency management)
3. Install [Node.js](https://nodejs.org/) (JavaScript runtime environment)
7. Clone this repo and open a terminal in the `pc4-ward` project root directory
8. Download project dependencies:
    ```sh
    npm install
    ```


### Browser Setup

Browser caching should be disabled when developer tools are open to prevent interference with
[`shadow-cljs`](https://github.com/thheller/shadow-cljs) hot reloading.

Custom formatters must be enabled in the browser before
[CLJS DevTools](https://github.com/binaryage/cljs-devtools) can display ClojureScript data in the
console in a more readable way.

#### Chrome/Chromium

1. Open [DevTools](https://developers.google.com/web/tools/chrome-devtools/) (Linux/Windows: `F12`
or `Ctrl-Shift-I`; macOS: `⌘-Option-I`)
2. Open DevTools Settings (Linux/Windows: `?` or `F1`; macOS: `?` or `Fn+F1`)
3. Select `Preferences` in the navigation menu on the left, if it is not already selected
4. Under the `Network` heading, enable the `Disable cache (while DevTools is open)` option
5. Under the `Console` heading, enable the `Enable custom formatters` option

#### Firefox

1. Open [Developer Tools](https://developer.mozilla.org/en-US/docs/Tools) (Linux/Windows: `F12` or
`Ctrl-Shift-I`; macOS: `⌘-Option-I`)
2. Open [Developer Tools Settings](https://developer.mozilla.org/en-US/docs/Tools/Settings)
(Linux/macOS/Windows: `F1`)
3. Under the `Advanced settings` heading, enable the `Disable HTTP Cache (when toolbox is open)`
option

Unfortunately, Firefox does not yet support custom formatters in their devtools. For updates, follow
the enhancement request in their bug tracker:
[1262914 - Add support for Custom Formatters in devtools](https://bugzilla.mozilla.org/show_bug.cgi?id=1262914).

## Development

### Running the App

Start a temporary local web server, build the app with the `dev` profile, and serve the app with
hot reload:

```sh
shadow-cljs -A:dev watch app

```

Please be patient; it may take over 20 seconds to see any output, and over 40 seconds to complete.

When `[:app] Build completed` appears in the output, browse to
[http://localhost:8280/](http://localhost:8280/).

[`shadow-cljs`](https://github.com/thheller/shadow-cljs) will automatically push ClojureScript code
changes to your browser on save. To prevent a few common issues, see
[Hot Reload in ClojureScript: Things to avoid](https://code.thheller.com/blog/shadow-cljs/2019/08/25/hot-reload-in-clojurescript.html#things-to-avoid).

Opening the app in your browser starts a
[ClojureScript browser REPL](https://clojurescript.org/reference/repl#using-the-browser-as-an-evaluation-environment),
to which you may now connect.

#### Connecting to the browser REPL from your editor

For IntelliJ (Cursive), simply create a new remote REPL run configuration, using the nREPL server port printed
by shadow cljs. The REPL starts in clj mode, so switch to cljs by simply typing

```clojure
(shadow.cljs.devtools.api/nrepl-select :app)
```

You'll then be able to directly execute cljs in the browser:

```clojure
(js/console.log "Hi there from the REPL" ) 
(js/alert "Hello, World")
```

See
[Shadow CLJS User's Guide: Editor Integration](https://shadow-cljs.github.io/docs/UsersGuide.html#_editor_integration).
Note that  `shadow-cljs -A dev watch app` expects that this project's running build id is
`app`, or the keyword `:app` in a Clojure context.

Alternatively, search the web for info on connecting to a `shadow-cljs` ClojureScript browser REPL
from your editor and configuration.

For example, in Vim / Neovim with `fireplace.vim`
1. Open a `.cljs` file in the project to activate `fireplace.vim`
2. In normal mode, execute the `Piggieback` command with this project's running build id, `:app`:
    ```vim
    :Piggieback :app
    ```

#### Connecting to the browser REPL from a terminal

1. Connect to the `shadow-cljs` nREPL:
    ```sh
    shadow-cljs -A dev browser-repl
    ```
    The REPL prompt, `shadow.user=>`, indicates that is a Clojure REPL, not ClojureScript.

2. In the REPL, switch the session to this project's running build id, `:app`:
    ```clj
    (shadow.cljs.devtools.api/nrepl-select :app)
    ```
    The REPL prompt changes to `cljs.user=>`, indicating that this is now a ClojureScript REPL.
3. See [`user.cljs`](dev/cljs/user.cljs) for symbols that are immediately accessible in the REPL
without needing to `require`.

### Running `shadow-cljs` Actions

See a list of [`shadow-cljs CLI`](https://shadow-cljs.github.io/docs/UsersGuide.html#_command_line)
actions:
```sh
shadow-cljs --help
```

Please be patient; it may take over 10 seconds to see any output. Also note that some actions shown
may not actually be supported, outputting "Unknown action." when run.

Run a shadow-cljs action on this project's build id (without the colon, just `app`):
```sh
shadow-cljs -A <env> <action> app
```
### Debug Logging

The `debug?` variable in [`config.cljs`](src/eldrix/pc4_ward/config.cljs) defaults to `true` in
[`dev`](#running-the-app) builds, and `false` in [`prod`](#production) builds.

Use `debug?` for logging or other tasks that should run only on `dev` builds:

```clj
(ns eldrix.pc4-ward.example
  (:require [eldrix.pc4-ward.config :as config])

(when config/debug?
  (println "This message will appear in the browser console only on dev builds."))
```

## Production

Build the app with the `prod` profile:

```sh
shadow-cljs -A prod release app
```

Please be patient; it may take over 15 seconds to see any output, and over 30 seconds to complete.

The `resources/public/js/compiled` directory is created, containing the compiled `app.js` and
`manifest.edn` files.

The [`resources/public`](resources/public/) directory contains the complete, production web front
end of your app.

Always inspect the `resources/public/js/compiled` directory prior to deploying the app.
