#include "display.h"
#include <Counter.h>
String message(){static Counter counter;return String("Count: ")+counter.next();}
