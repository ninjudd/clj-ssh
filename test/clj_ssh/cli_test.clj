(ns clj-ssh.cli-test
  (:use
   clojure.test
   clj-ssh.cli
   clj-ssh.test-keys
   [clj-ssh.ssh
    :only [connect connected? disconnect ssh-agent ssh-agent? with-connection]]
   [clj-ssh.test-utils
    :only [quiet-ssh-logging sftp-monitor sftp-monitor-done home]])
  (:require [clojure.java.io :as io])
  (:import com.jcraft.jsch.JSch))

(use-fixtures :once quiet-ssh-logging)

(deftest with-ssh-agent-test
  (testing "initialised"
    (is (ssh-agent? *ssh-agent*)))
  (testing "system ssh-agent"
    (with-ssh-agent (ssh-agent {})
      (is (ssh-agent? *ssh-agent*))
      (is (pos? (count (.getIdentityNames *ssh-agent*))))))
  (testing "local ssh-agent"
    (with-ssh-agent (ssh-agent {:use-system-ssh-agent false})
      (is (ssh-agent? *ssh-agent*))
      (is (zero? (count (.getIdentityNames *ssh-agent*))))))
  (let [agent (ssh-agent {})]
    (with-ssh-agent agent
      (is (= *ssh-agent* agent)))))

(deftest add-identity-test
  (let [key (private-key-path)]
    (with-ssh-agent (ssh-agent {:use-system-ssh-agent false})
      (add-identity :private-key-path key)
      (is (= 1 (count (.getIdentityNames *ssh-agent*))))
      (add-identity :agent *ssh-agent* :private-key-path key)))
  (testing "passing byte arrays"
    (with-ssh-agent (ssh-agent {:use-system-ssh-agent false})
      (add-identity
       :name "name"
       :private-key (.getBytes (slurp (private-key-path)))
       :public-key (.getBytes (slurp (public-key-path))))
      (is (= 1 (count (.getIdentityNames *ssh-agent*))))
      (is (= "name" (first (.getIdentityNames *ssh-agent*)))))))

(deftest has-identity?-test
  (let [key (private-key-path)]
    (with-ssh-agent (ssh-agent {:use-system-ssh-agent false})
      (is (not (has-identity? key)))
      (add-identity :private-key-path key)
      (is (= 1 (count (.getIdentityNames *ssh-agent*))))
      (is (has-identity? key))
      (add-identity :private-key-path key)
      (is (= 1 (count (.getIdentityNames *ssh-agent*)))))))

(deftest session-test
  (with-ssh-agent (ssh-agent {:use-system-ssh-agent false})
    (let [session (session "localhost" :username (username) :port 22)]
      (is (instance? com.jcraft.jsch.Session session))
      (is (not (connected? session))))))

(deftest session-connect-test
  (with-ssh-agent (ssh-agent {:use-system-ssh-agent false})
    (default-session-options
      {:username (username) :strict-host-key-checking :no})
    (add-identity :private-key-path (private-key-path))
    (let [session (session "localhost")]
      (is (instance? com.jcraft.jsch.Session session))
      (is (not (connected? session)))
      (connect session)
      (is (connected? session))
      (disconnect session)
      (is (not (connected? session))))
    (let [session (session "localhost")]
      (with-connection session
        (is (connected? session)))
      (is (not (connected? session)))))
  (with-ssh-agent (ssh-agent {:use-system-ssh-agent false})
    (add-identity-with-keychain
      :private-key-path (encrypted-private-key-path)
      :name "clj-ssh")
    (let [session (session "localhost")]
      (is (instance? com.jcraft.jsch.Session session))
      (is (not (connected? session)))
      (connect session)
      (is (connected? session))
      (disconnect session)
      (is (not (connected? session))))
    (let [session (session "localhost")]
      (with-connection session
        (is (connected? session)))
      (is (not (connected? session)))))
  (with-ssh-agent (ssh-agent {})
    (let [session (session "localhost")]
      (is (instance? com.jcraft.jsch.Session session))
      (is (not (connected? session)))
      (connect session)
      (is (connected? session))
      (disconnect session)
      (is (not (connected? session))))
    (let [session (session "localhost")]
      (with-connection session
        (is (connected? session)))
      (is (not (connected? session))))))

(deftest ssh-test
  (with-ssh-agent (ssh-agent {:use-system-ssh-agent false})
    (add-identity :private-key-path (private-key-path))
    (default-session-options
      {:username (username) :strict-host-key-checking :no})
    (let [{:keys [exit out]} (ssh "localhost" :in "echo hello")]
      (is (zero? exit))
      (is (.contains out "hello")))
    (let [{:keys [exit out err]} (ssh "localhost" :cmd "/bin/bash -c 'ls /'")]
      (is (zero? exit))
      (is (.contains out "bin"))
      (is (= "" err)))
    (let [{:keys [exit out err]} (ssh "localhost" "/bin/bash -c 'ls /'")]
      (is (zero? exit))
      (is (.contains out "bin"))
      (is (= "" err)))
    (let [{:keys [exit out err]} (ssh "localhost" "/bin/bash" "-c" "'ls /'")]
      (is (zero? exit))
      (is (.contains out "bin"))
      (is (= "" err)))
    (let [{:keys [exit out]}
          (ssh "localhost" :in "echo hello" :username (username))]
      (is (zero? exit))
      (is (.contains out "hello")))
    (let [{:keys [exit out err]}
          (ssh "localhost" :cmd "/bin/bash -c 'ls /'" :username (username))]
      (is (zero? exit))
      (is (.contains out "bin"))
      (is (= "" err)))
    (let [{:keys [exit out]}
          (ssh "localhost" :in "tty -s" :pty true :username (username))]
      (is (zero? exit)))
    (let [{:keys [exit out]}
          (ssh "localhost" :in "tty -s" :pty false :username (username))]
      (is (= 1 exit)))
    (let [{:keys [exit out]}
          (ssh "localhost" :in "ssh-add -l" :agent-forwarding true
               :username (username))]
      (is (zero? exit)))))


(deftest sftp-test
  (let [home (home)
        dir (sftp "localhost" :ls home)]
    (sftp "localhost" :cd "/")
    (is (= home (sftp "localhost" :pwd)))
    (let [tmpfile1 (java.io.File/createTempFile "clj-ssh" "test")
          tmpfile2 (java.io.File/createTempFile "clj-ssh" "test")
          file1 (.getPath tmpfile1)
          file2 (.getPath tmpfile2)
          content "content"
          content2 "content2"]
      (try
       (.setWritable tmpfile1 true false)
       (.setWritable tmpfile2 true false)
       (io/copy content tmpfile1)
       (sftp "localhost" :put file1 file2)
       (is (= content (slurp file2)))
       (io/copy content2 tmpfile2)
       (sftp "localhost" :get file2 file1)
       (is (= content2 (slurp file1)))
       (sftp
        "localhost" :put (java.io.ByteArrayInputStream. (.getBytes content)) file1)
       (is (= content (slurp file1)))
       (let [[monitor state] (sftp-monitor)]
         (sftp "localhost" :put (java.io.ByteArrayInputStream. (.getBytes content))
               file2 :with-monitor monitor)
         (is (sftp-monitor-done state)))
       (is (= content (slurp file2)))
       (finally
        (.delete tmpfile1)
        (.delete tmpfile2))))))
