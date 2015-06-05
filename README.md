# conf_program

A combination RESTful web service, jQuery front page, and Twitter bot. Meant to help me practice clojure programming.

See http://farfarfutureoflibraries.org and @farfar_conf on Twitter.

## Usage

One option is to start a screen, then do:

    lein trampoline ring server-headless

Another is to build a .war file and then use tomcat or jetty to serve it.

    lein trampoline ring uberwar

See: https://fitacular.com/blog/clojure/2014/07/14/deploy-clojure-tomcat-nginx/