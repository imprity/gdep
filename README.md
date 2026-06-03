# gdep

Written to grep around gradle dependencies.

It primarily manages gradle project external dependencies with [Gradle Tooling API.](https://docs.gradle.org/current/userguide/tooling_api.html)

It does so by unzipping external library source jar files Gradle Tooling API tells us and placing them under a `gdep-cache` folder that sits next to `gdep.exe`.

# Building

You need JDK 25 and Go compiler version 1.24.2 (though Go compiler version might not matter too much).

Do

``` cmd
> build.bat
```

and you'll see `out` folder with `gdep.exe`.

And if you are wondering why we use Go compiler, it's because we use it as a thin launcher that basically does `java -jar gdep.jar`.

And JDK is 25 solely because of Nullaway's Jspecify support. Check out [here.](https://github.com/uber/NullAway/wiki/JSpecify-Support#supported-jdk-versions)

# Running

For running, you need JRE 17 or higher.

Simply do
``` cmd
> gdep.exe help 
gdep

usage:

help : prints this message
dirs : list source directories
files : list source files
pack : search files using class path. e.g. gdep pack o.s.w.s.DispatcherServlet
```

inside any gradle project directory to see available commands.

# TODO

- Support linux build.
- Cache Gradle Tooling API output.
- Rotate `gdep-log.txt` of `gdep.exe`.
- Write tests or something.
