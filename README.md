# gdep

Written to grep around gradle dependencies.

It primarily manages gradle project external dependencies with [Gradle Tooling API.](https://docs.gradle.org/current/userguide/tooling_api.html)

It does so by unzipping external library source jar files Gradle Tooling API tells us and placing them under a `gdep-cache` folder that sits next to `gdep.exe`.

# Building

You need JDK 17 and Go compiler version 1.24.2.

Do

``` cmd
> build.bat
```

and you'll see `out` folder with `gdep.exe`.

And if you are wondering why we use Go compiler, it's because java is slow at the startup. So we actually only use java for communicating with gradle, nothing else.

Target java release version is java 8 because according to [Gradle Tooling API documentation](https://docs.gradle.org/current/userguide/tooling_api.html#sec:embedding_compatibility), JRE 8 is the lowest version that supports Gradle Tooling API.

And JDK 17 was the lowest JDK version that could build this project

# Running

For running, you need JRE 8 or higher.

Simply do
``` cmd
> gdep.exe help
gdep

usage:

flags:
  -ignore-cache
    	ignore Gradle Tooling API cache
  -pprof string
    	write cpu profile to given file
commands:
  help : prints this message
  dirs : list source directories
  files : list source files
  pack : search files using class path. e.g. gdep pack o.s.w.s.DispatcherServlet

example with flag:
  gdep --pprof=cpu.pprof pack o.s.w.s.DispatcherServlet
```

inside any gradle project directory to see available commands.

# Settings

Create `gdep-settings.json` file next to gdep.exe to change how gdep behaves.

``` json
{
  "JavaHome" : "/path/to/your/java",
  "CacheDir" : "/path/to/gdep/cache"
}
```

# TODO

- Write tests or something.
