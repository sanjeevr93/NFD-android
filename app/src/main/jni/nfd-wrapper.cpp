/* -*- Mode:C++; c-file-style:"gnu"; indent-tabs-mode:nil; -*- */
/**
 * Copyright (c) 2015-2018 Regents of the University of California
 *
 * This file is part of NFD (Named Data Networking Forwarding Daemon) Android.
 * See AUTHORS.md for complete list of NFD Android authors and contributors.
 *
 * NFD Android is free software: you can redistribute it and/or modify it under the terms
 * of the GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 *
 * NFD Android is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR
 * PURPOSE.  See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * NFD Android, e.g., in COPYING.md file.  If not, see <http://www.gnu.org/licenses/>.
 */

#include "nfd-wrapper.hpp"

#include "daemon/nfd.hpp"
#include "rib/service.hpp"

#include "core/config-file.hpp"
#include "core/global-io.hpp"
#include "core/logger.hpp"
#include "core/privilege-helper.hpp"

#include <boost/property_tree/info_parser.hpp>
#include <boost/thread.hpp>
#include <mutex>
#include <ndn-cxx/util/logging.hpp>
#include <stdlib.h>

NFD_LOG_INIT(NfdWrapper);

namespace nfd {


// A little bit of cheating to make sure NFD can be properly restarted

namespace scheduler {
// defined in scheduler.cpp
void
resetGlobalScheduler();
} // namespace scheduler

void
resetGlobalIoService();

class Runner
{
public:
  Runner()
    : m_io(nullptr)
  {
    std::string initialConfig = R"CONF(
      log
      {
        default_level ALL
      }
      tables
      {
        cs_max_packets 100
        strategy_choice
        {
          /               /localhost/nfd/strategy/best-route
          /localhost      /localhost/nfd/strategy/multicast
          /localhost/nfd  /localhost/nfd/strategy/best-route
          /ndn/broadcast  /localhost/nfd/strategy/multicast
          /ndn/multicast  /localhost/nfd/strategy/multicast
        }
      }
      face_system
      {
        tcp
        udp
        {
          mcast no
        }
        websocket
      }
      authorizations
      {
        authorize
        {
          certfile any
          privileges
          {
            faces
            fib
            cs
            strategy-choice
          }
        }
      }
      rib
      {
        localhost_security
        {
          trust-anchor
          {
            type any
          }
        }
        auto_prefix_propagate
        {
          refresh_interval 300
        }
      }
  )CONF";

    std::istringstream input(initialConfig);
    boost::property_tree::read_info(input, m_config);

    std::unique_lock<std::mutex> lock(m_pointerMutex);
    m_nfd.reset(new Nfd(m_config, m_keyChain));
    m_nrd.reset(new rib::Service(m_config, m_keyChain));

    m_nfd->initialize();
    m_nrd->initialize();
  }

  ~Runner()
  {
    stop();
    m_io->reset();
  }

  void
  start()
  {
    {
      std::unique_lock<std::mutex> lock(m_pointerMutex);
      m_io = &getGlobalIoService();
    }

    m_io->run();
    m_io->reset();
  }

  void
  stop()
  {
    std::unique_lock<std::mutex> lock(m_pointerMutex);

    m_io->post([this] {
        m_io->stop();
        this->m_nrd.reset();
        this->m_nfd.reset();
      });
  }

private:
  std::mutex m_pointerMutex;
  boost::asio::io_service* m_io;
  ndn::KeyChain m_keyChain;
  unique_ptr<Nfd> m_nfd; // will use globalIoService
  unique_ptr<rib::Service> m_nrd; // will use globalIoService

  nfd::ConfigSection m_config;
};

static unique_ptr<Runner> g_runner;
static boost::thread g_thread;
static std::map<std::string, std::string> g_params;

} // namespace nfd


std::map<std::string, std::string>
getParams(JNIEnv* env, jobject jParams)
{
  std::map<std::string, std::string> params;

  jclass jcMap = env->GetObjectClass(jParams);
  jclass jcSet = env->FindClass("java/util/Set");
  jclass jcIterator = env->FindClass("java/util/Iterator");
  jclass jcMapEntry = env->FindClass("java/util/Map$Entry");

  jmethodID jcMapEntrySet      = env->GetMethodID(jcMap,      "entrySet", "()Ljava/util/Set;");
  jmethodID jcSetIterator      = env->GetMethodID(jcSet,      "iterator", "()Ljava/util/Iterator;");
  jmethodID jcIteratorHasNext  = env->GetMethodID(jcIterator, "hasNext",  "()Z");
  jmethodID jcIteratorNext     = env->GetMethodID(jcIterator, "next",     "()Ljava/lang/Object;");
  jmethodID jcMapEntryGetKey   = env->GetMethodID(jcMapEntry, "getKey",   "()Ljava/lang/Object;");
  jmethodID jcMapEntryGetValue = env->GetMethodID(jcMapEntry, "getValue", "()Ljava/lang/Object;");

  jobject jParamsEntrySet = env->CallObjectMethod(jParams, jcMapEntrySet);
  jobject jParamsIterator = env->CallObjectMethod(jParamsEntrySet, jcSetIterator);
  jboolean bHasNext = env->CallBooleanMethod(jParamsIterator, jcIteratorHasNext);
  while (bHasNext) {
    jobject entry = env->CallObjectMethod(jParamsIterator, jcIteratorNext);

    jstring jKey = (jstring)env->CallObjectMethod(entry, jcMapEntryGetKey);
    jstring jValue = (jstring)env->CallObjectMethod(entry, jcMapEntryGetValue);

    const char* cKey = env->GetStringUTFChars(jKey, nullptr);
    const char* cValue = env->GetStringUTFChars(jValue, nullptr);

    params.insert(std::make_pair(cKey, cValue));

    env->ReleaseStringUTFChars(jKey, cKey);
    env->ReleaseStringUTFChars(jValue, cValue);

    bHasNext = env->CallBooleanMethod(jParamsIterator, jcIteratorHasNext);
  }

  return params;
}


JNIEXPORT void JNICALL
Java_net_named_1data_nfd_service_NfdService_startNfd(JNIEnv* env, jclass, jobject jParams)
{
  if (nfd::g_runner.get() == nullptr) {
    nfd::g_params = getParams(env, jParams);

    // set/update HOME environment variable
    ::setenv("HOME", nfd::g_params["homePath"].c_str(), true);
    NFD_LOG_INFO("Use [" << nfd::g_params["homePath"] << "] as a security storage");

    nfd::g_thread = boost::thread([] {
        nfd::scheduler::resetGlobalScheduler();
        nfd::resetGlobalIoService();

        NFD_LOG_INFO("Starting NFD...");
        try {
          nfd::g_runner.reset(new nfd::Runner());
          nfd::g_runner->start();
        }
        catch (const std::exception& e) {
          NFD_LOG_FATAL(e.what());
        }
        catch (const nfd::PrivilegeHelper::Error& e) {
          NFD_LOG_FATAL("PrivilegeHelper: " << e.what());
        }
        catch (...) {
          NFD_LOG_FATAL("Unknown fatal error");
        }

        nfd::g_runner.reset();
        nfd::scheduler::resetGlobalScheduler();
        nfd::resetGlobalIoService();
        NFD_LOG_INFO("NFD stopped");
      });
  }
}

JNIEXPORT void JNICALL
Java_net_named_1data_nfd_service_NfdService_stopNfd(JNIEnv*, jclass)
{
  if (nfd::g_runner.get() != nullptr) {
    NFD_LOG_INFO("Stopping NFD...");
    nfd::g_runner->stop();
    // do not block anything
  }
}

JNIEXPORT jboolean JNICALL
Java_net_named_1data_nfd_service_NfdService_isNfdRunning(JNIEnv*, jclass)
{
    return nfd::g_runner.get() != nullptr;
}

JNIEXPORT jobject JNICALL
Java_net_named_1data_nfd_service_NfdService_getNfdLogModules(JNIEnv* env, jclass)
{
  jclass jcLinkedList = env->FindClass("java/util/LinkedList");
  jmethodID jcLinkedListConstructor = env->GetMethodID(jcLinkedList, "<init>", "()V");
  jmethodID jcLinkedListAdd = env->GetMethodID(jcLinkedList, "add", "(Ljava/lang/Object;)Z");

  jobject jModules = env->NewObject(jcLinkedList, jcLinkedListConstructor);

  for (const auto& module : ndn::util::Logging::getLoggerNames()) {
    jstring jModule = env->NewStringUTF(module.c_str());
    env->CallBooleanMethod(jModules, jcLinkedListAdd, jModule);
  }

  return jModules;
}
