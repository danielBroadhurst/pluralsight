﻿using AM_SupplierManager_Core.Common;
using System;
using System.Collections.Generic;

namespace AM_SupplierManager_Core
{
    public class Supplier
    {
        public int Id { get; set; }
        public int AccountId { get; set; }
        public string ContactName { get; set; }
        public string Company { get; set; }
        public string Email { get; set; }
        public string Phone { get; set; }
        public string Mobile { get; set; }
        public string Fax { get; set; }
        public string Website { get; set; }
    }
}
